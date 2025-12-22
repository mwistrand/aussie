package aussie.core.service.auth;

import java.time.Duration;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.AuthRateLimitConfig;
import aussie.spi.FailedAttemptRepository;
import aussie.spi.FailedAttemptRepository.LockoutInfo;

/**
 * Service for authentication rate limiting (brute force protection).
 *
 * <p>
 * Protects authentication endpoints against:
 * <ul>
 * <li>Credential stuffing attacks</li>
 * <li>API key brute force attempts</li>
 * <li>Session enumeration attacks</li>
 * </ul>
 *
 * <p>
 * Failed attempts are tracked by both IP address and identifier (username,
 * email, or API key prefix) to prevent distributed attacks while minimizing
 * false positives for legitimate users behind shared IPs.
 *
 * <p>
 * Progressive lockout increases lockout duration for repeat offenders.
 */
@ApplicationScoped
@IfBuildProperty(name = "aussie.auth.rate-limit.enabled", stringValue = "true", enableIfMissing = true)
public class AuthRateLimitService {

    private static final Logger LOG = Logger.getLogger(AuthRateLimitService.class);

    private static final String IP_PREFIX = "ip:";
    private static final String USER_PREFIX = "user:";
    private static final String API_KEY_PREFIX = "apikey:";

    private final AuthRateLimitConfig config;
    private final FailedAttemptRepository repository;

    public AuthRateLimitService(AuthRateLimitConfig config, FailedAttemptRepository repository) {
        this.config = config;
        this.repository = repository;
    }

    /**
     * Check if authentication should be rate limited.
     *
     * <p>
     * Checks both IP-based and identifier-based lockouts.
     *
     * @param ip         client IP address (may be null)
     * @param identifier username, email, or API key prefix (may be null)
     * @return Uni with rate limit result
     */
    public Uni<RateLimitResult> checkAuthLimit(String ip, String identifier) {
        if (!config.enabled()) {
            return Uni.createFrom().item(RateLimitResult.allow());
        }

        final var ipKey = ip != null && config.trackByIp() ? IP_PREFIX + ip : null;
        final var identifierKey = identifier != null && config.trackByIdentifier() ? USER_PREFIX + identifier : null;

        // Check IP lockout first
        final Uni<RateLimitResult> ipCheck =
                ipKey != null ? checkLockout(ipKey) : Uni.createFrom().item(RateLimitResult.allow());

        return ipCheck.flatMap(ipResult -> {
            if (!ipResult.allowed()) {
                return Uni.createFrom().item(ipResult);
            }

            // Check identifier lockout
            if (identifierKey != null) {
                return checkLockout(identifierKey);
            }

            return Uni.createFrom().item(RateLimitResult.allow());
        });
    }

    /**
     * Check if authentication for an API key should be rate limited.
     *
     * @param ip        client IP address (may be null)
     * @param keyPrefix first 8 characters of the API key
     * @return Uni with rate limit result
     */
    public Uni<RateLimitResult> checkApiKeyLimit(String ip, String keyPrefix) {
        if (!config.enabled()) {
            return Uni.createFrom().item(RateLimitResult.allow());
        }

        final var ipKey = ip != null && config.trackByIp() ? IP_PREFIX + ip : null;
        final var keyKey = keyPrefix != null && config.trackByIdentifier() ? API_KEY_PREFIX + keyPrefix : null;

        // Check IP lockout first
        Uni<RateLimitResult> ipCheck =
                ipKey != null ? checkLockout(ipKey) : Uni.createFrom().item(RateLimitResult.allow());

        return ipCheck.flatMap(ipResult -> {
            if (!ipResult.allowed()) {
                return Uni.createFrom().item(ipResult);
            }

            // Check API key prefix lockout
            if (keyKey != null) {
                return checkLockout(keyKey);
            }

            return Uni.createFrom().item(RateLimitResult.allow());
        });
    }

    private Uni<RateLimitResult> checkLockout(String key) {
        return repository.isLockedOut(key).flatMap(lockedOut -> {
            if (lockedOut) {
                return repository.getLockoutExpiry(key).map(expiry -> {
                    final var retryAfter = expiry != null
                            ? Duration.between(Instant.now(), expiry).toSeconds()
                            : config.lockoutDuration().toSeconds();
                    LOG.debugf("Auth blocked for %s: locked out until %s", key, expiry);
                    return RateLimitResult.blocked(key, Math.max(0, retryAfter), expiry);
                });
            }
            return Uni.createFrom().item(RateLimitResult.allow());
        });
    }

    /**
     * Record a failed authentication attempt.
     *
     * <p>
     * If the attempt count reaches the threshold, the key is locked out.
     *
     * @param ip         client IP address (may be null)
     * @param identifier username, email, or API key prefix (may be null)
     * @param reason     failure reason for logging
     * @return Uni with lockout result (indicates if lockout was triggered)
     */
    public Uni<LockoutResult> recordFailedAttempt(String ip, String identifier, String reason) {
        if (!config.enabled()) {
            return Uni.createFrom().item(LockoutResult.notLocked(0, 0));
        }

        final var ipKey = ip != null && config.trackByIp() ? IP_PREFIX + ip : null;
        final var identifierKey = identifier != null && config.trackByIdentifier() ? USER_PREFIX + identifier : null;

        LOG.debugf("Recording failed auth attempt: ip=%s, identifier=%s, reason=%s", ip, identifier, reason);

        // Record both IP and identifier attempts
        Uni<LockoutResult> ipResult = ipKey != null
                ? recordAttemptAndMaybeLockout(ipKey, reason)
                : Uni.createFrom().item(LockoutResult.notLocked(0, 0));

        return ipResult.flatMap(ipLockout -> {
            if (identifierKey != null) {
                return recordAttemptAndMaybeLockout(identifierKey, reason).map(identifierLockout -> {
                    // Return the more severe result (locked out wins)
                    if (ipLockout.lockedOut() || identifierLockout.lockedOut()) {
                        return ipLockout.lockedOut() ? ipLockout : identifierLockout;
                    }
                    // Return the one with more attempts
                    return ipLockout.attempts() >= identifierLockout.attempts() ? ipLockout : identifierLockout;
                });
            }
            return Uni.createFrom().item(ipLockout);
        });
    }

    /**
     * Record a failed API key authentication attempt.
     *
     * @param ip        client IP address (may be null)
     * @param keyPrefix first 8 characters of the API key
     * @param reason    failure reason for logging
     * @return Uni with lockout result
     */
    public Uni<LockoutResult> recordFailedApiKeyAttempt(String ip, String keyPrefix, String reason) {
        if (!config.enabled()) {
            return Uni.createFrom().item(LockoutResult.notLocked(0, 0));
        }

        final var ipKey = ip != null && config.trackByIp() ? IP_PREFIX + ip : null;
        final var keyKey = keyPrefix != null && config.trackByIdentifier() ? API_KEY_PREFIX + keyPrefix : null;

        LOG.debugf("Recording failed API key attempt: ip=%s, keyPrefix=%s, reason=%s", ip, keyPrefix, reason);

        // Record both IP and key prefix attempts
        Uni<LockoutResult> ipResult = ipKey != null
                ? recordAttemptAndMaybeLockout(ipKey, reason)
                : Uni.createFrom().item(LockoutResult.notLocked(0, 0));

        return ipResult.flatMap(ipLockout -> {
            if (keyKey != null) {
                return recordAttemptAndMaybeLockout(keyKey, reason).map(keyLockout -> {
                    if (ipLockout.lockedOut() || keyLockout.lockedOut()) {
                        return ipLockout.lockedOut() ? ipLockout : keyLockout;
                    }
                    return ipLockout.attempts() >= keyLockout.attempts() ? ipLockout : keyLockout;
                });
            }
            return Uni.createFrom().item(ipLockout);
        });
    }

    private Uni<LockoutResult> recordAttemptAndMaybeLockout(String key, String reason) {
        return repository.recordFailedAttempt(key, config.failedAttemptWindow()).flatMap(count -> {
            final var remaining = config.maxFailedAttempts() - count.intValue();

            if (count >= config.maxFailedAttempts()) {
                // Trigger lockout with progressive duration
                return repository.getLockoutCount(key).flatMap(lockoutCount -> {
                    final var lockoutDuration = calculateProgressiveLockout(lockoutCount);
                    return repository
                            .recordLockout(key, lockoutDuration, reason)
                            .map(v -> {
                                LOG.warnf(
                                        "Auth lockout triggered for %s: attempts=%d, duration=%s",
                                        key, count, lockoutDuration);
                                return LockoutResult.locked(key, count.intValue(), lockoutDuration.toSeconds());
                            });
                });
            }

            LOG.debugf("Failed attempt recorded for %s: count=%d, remaining=%d", key, count, Math.max(0, remaining));
            return Uni.createFrom().item(LockoutResult.notLocked(count.intValue(), Math.max(0, remaining)));
        });
    }

    private Duration calculateProgressiveLockout(int previousLockouts) {
        if (config.progressiveLockoutMultiplier() <= 1.0) {
            return config.lockoutDuration();
        }

        // Calculate progressive duration: base * multiplier^lockoutCount
        var multiplier = Math.pow(config.progressiveLockoutMultiplier(), previousLockouts);
        var durationSeconds = (long) (config.lockoutDuration().toSeconds() * multiplier);
        var progressiveDuration = Duration.ofSeconds(durationSeconds);

        // Cap at max lockout duration
        if (progressiveDuration.compareTo(config.maxLockoutDuration()) > 0) {
            return config.maxLockoutDuration();
        }

        return progressiveDuration;
    }

    /**
     * Clear failed attempts after successful authentication.
     *
     * @param ip         client IP address (may be null)
     * @param identifier username, email, or API key prefix (may be null)
     * @return Uni completing when cleared
     */
    public Uni<Void> clearFailedAttempts(String ip, String identifier) {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        final var ipKey = ip != null && config.trackByIp() ? IP_PREFIX + ip : null;
        final var identifierKey = identifier != null && config.trackByIdentifier() ? USER_PREFIX + identifier : null;

        LOG.debugf("Clearing failed attempts: ip=%s, identifier=%s", ip, identifier);

        Uni<Void> ipClear = ipKey != null
                ? repository.clearFailedAttempts(ipKey)
                : Uni.createFrom().voidItem();

        return ipClear.flatMap(v -> {
            if (identifierKey != null) {
                return repository.clearFailedAttempts(identifierKey);
            }
            return Uni.createFrom().voidItem();
        });
    }

    /**
     * Clear a lockout for a specific key.
     *
     * @param key the lockout key (ip:xxx, user:xxx, or apikey:xxx)
     * @return Uni completing when cleared
     */
    public Uni<Void> clearLockout(String key) {
        LOG.infof("Clearing lockout for %s", key);
        return repository.clearLockout(key).call(() -> repository.clearFailedAttempts(key));
    }

    /**
     * Clear lockout for an IP address.
     *
     * @param ip the IP address
     * @return Uni completing when cleared
     */
    public Uni<Void> clearIpLockout(String ip) {
        return clearLockout(IP_PREFIX + ip);
    }

    /**
     * Clear lockout for a user identifier.
     *
     * @param identifier the username or email
     * @return Uni completing when cleared
     */
    public Uni<Void> clearUserLockout(String identifier) {
        return clearLockout(USER_PREFIX + identifier);
    }

    /**
     * Check if a specific key is locked out.
     *
     * @param key the lockout key
     * @return Uni with true if locked out
     */
    public Uni<Boolean> isLockedOut(String key) {
        return repository.isLockedOut(key);
    }

    /**
     * Get lockout info for a key.
     *
     * @param key the lockout key
     * @return Uni with lockout info or null if not locked out
     */
    public Uni<LockoutInfo> getLockoutInfo(String key) {
        return repository
                .streamAllLockouts()
                .filter(info -> info.key().equals(key))
                .collect()
                .first();
    }

    /**
     * Stream all current lockouts.
     *
     * @return Multi of lockout info records
     */
    public Multi<LockoutInfo> streamAllLockouts() {
        return repository.streamAllLockouts();
    }

    /**
     * Get the failed attempt count for a key.
     *
     * @param key the lockout key
     * @return Uni with attempt count
     */
    public Uni<Long> getFailedAttemptCount(String key) {
        return repository.getFailedAttemptCount(key);
    }

    /**
     * Check if rate limiting is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Result of a rate limit check.
     *
     * @param isAllowed         true if authentication should proceed
     * @param key               the key that triggered the block (null if allowed)
     * @param retryAfterSeconds seconds until retry is allowed (0 if allowed)
     * @param lockoutExpiry     when the lockout expires (null if allowed)
     */
    public record RateLimitResult(boolean isAllowed, String key, long retryAfterSeconds, Instant lockoutExpiry) {

        public boolean allowed() {
            return isAllowed;
        }

        public static RateLimitResult allow() {
            return new RateLimitResult(true, null, 0, null);
        }

        public static RateLimitResult blocked(String key, long retryAfterSeconds, Instant lockoutExpiry) {
            return new RateLimitResult(false, key, retryAfterSeconds, lockoutExpiry);
        }
    }

    /**
     * Result of recording a failed attempt.
     *
     * @param lockedOut         true if lockout was triggered
     * @param key               the key that was locked out (null if not locked)
     * @param attempts          current attempt count
     * @param remainingAttempts attempts remaining before lockout (0 if locked)
     * @param lockoutSeconds    lockout duration in seconds (0 if not locked)
     */
    public record LockoutResult(
            boolean lockedOut, String key, int attempts, int remainingAttempts, long lockoutSeconds) {

        public static LockoutResult notLocked(int attempts, int remaining) {
            return new LockoutResult(false, null, attempts, remaining, 0);
        }

        public static LockoutResult locked(String key, int attempts, long lockoutSeconds) {
            return new LockoutResult(true, key, attempts, 0, lockoutSeconds);
        }
    }
}
