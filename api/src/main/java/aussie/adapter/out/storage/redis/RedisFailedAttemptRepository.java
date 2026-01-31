package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.arc.DefaultBean;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.Metrics;
import aussie.spi.FailedAttemptRepository;

/**
 * Redis implementation of FailedAttemptRepository.
 *
 * <p>This is the default implementation for production deployments.
 * Failed attempt counters and lockouts are stored with automatic TTL expiration.
 *
 * <p>Key format:
 * <ul>
 *   <li>Failed attempts: {@code aussie:auth:failed:{key}}</li>
 *   <li>Lockout: {@code aussie:auth:lockout:{key}} (hash with lockedAt, expiresAt, reason, failedAttempts)</li>
 *   <li>Lockout count: {@code aussie:auth:lockout-count:{key}}</li>
 * </ul>
 *
 * <p>Platform teams can provide custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class CustomFailedAttemptRepository implements FailedAttemptRepository {
 *     // Custom implementation
 * }
 * }</pre>
 */
@ApplicationScoped
@DefaultBean
public class RedisFailedAttemptRepository implements FailedAttemptRepository {

    private static final Logger LOG = Logger.getLogger(RedisFailedAttemptRepository.class);

    private static final String FAILED_PREFIX = "aussie:auth:failed:";
    private static final String LOCKOUT_PREFIX = "aussie:auth:lockout:";
    private static final String LOCKOUT_COUNT_PREFIX = "aussie:auth:lockout-count:";

    // Lockout hash fields
    private static final String FIELD_LOCKED_AT = "lockedAt";
    private static final String FIELD_EXPIRES_AT = "expiresAt";
    private static final String FIELD_REASON = "reason";
    private static final String FIELD_FAILED_ATTEMPTS = "failedAttempts";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveHashCommands<String, String, String> hashCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final RedisTimeoutHelper timeoutHelper;

    @Inject
    public RedisFailedAttemptRepository(
            ReactiveRedisDataSource redisDataSource, ResiliencyConfig resiliencyConfig, Metrics metrics) {
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.hashCommands = redisDataSource.hash(String.class, String.class, String.class);
        this.keyCommands = redisDataSource.key(String.class);
        this.timeoutHelper =
                new RedisTimeoutHelper(resiliencyConfig.redis().operationTimeout(), metrics, "FailedAttemptRepository");
        LOG.info("Initialized Redis failed attempt repository");
    }

    @Override
    public Uni<Long> recordFailedAttempt(String key, Duration windowDuration) {
        final var redisKey = FAILED_PREFIX + key;
        final var ttlSeconds = windowDuration.toSeconds();

        // INCR + EXPIRE in a pipeline for atomicity
        var operation = valueCommands
                .incr(redisKey)
                .call(count -> keyCommands.expire(redisKey, ttlSeconds))
                .invoke(count -> LOG.debugf("Recorded failed attempt for %s: count=%d", key, count));
        // Fail-open: return 0 on timeout (allow request)
        return timeoutHelper.withTimeoutFallback(operation, "recordFailedAttempt", () -> 0L);
    }

    @Override
    public Uni<Long> getFailedAttemptCount(String key) {
        final var redisKey = FAILED_PREFIX + key;
        var operation = valueCommands.get(redisKey).map(value -> value != null ? Long.parseLong(value) : 0L);
        // Fail-open: return 0 on timeout (no failed attempts = allow)
        return timeoutHelper.withTimeoutFallback(operation, "getFailedAttemptCount", () -> 0L);
    }

    @Override
    public Uni<Void> clearFailedAttempts(String key) {
        final var redisKey = FAILED_PREFIX + key;
        var operation = keyCommands
                .del(redisKey)
                .replaceWithVoid()
                .invoke(() -> LOG.debugf("Cleared failed attempts for %s", key));
        return timeoutHelper.withTimeoutSilent(operation, "clearFailedAttempts");
    }

    @Override
    public Uni<Void> recordLockout(String key, Duration lockoutDuration, String reason) {
        final var lockoutKey = LOCKOUT_PREFIX + key;
        final var countKey = LOCKOUT_COUNT_PREFIX + key;
        final var ttlSeconds = lockoutDuration.toSeconds();
        final var now = Instant.now();
        final var expiresAt = now.plus(lockoutDuration);

        // Get failed attempt count first
        var operation = getFailedAttemptCount(key).flatMap(failedCount -> {
            // Store lockout info as a hash
            final var fields = Map.of(
                    FIELD_LOCKED_AT, String.valueOf(now.toEpochMilli()),
                    FIELD_EXPIRES_AT, String.valueOf(expiresAt.toEpochMilli()),
                    FIELD_REASON, reason != null ? reason : "max_failed_attempts",
                    FIELD_FAILED_ATTEMPTS, String.valueOf(failedCount));

            // Use setex for atomic set+expire on lockout count
            final var countTtl = Duration.ofDays(30).toSeconds();
            return hashCommands
                    .hset(lockoutKey, fields)
                    .call(() -> keyCommands.expire(lockoutKey, ttlSeconds))
                    .call(() -> getLockoutCount(key))
                    .call(currentCount -> valueCommands.setex(countKey, countTtl, String.valueOf(currentCount + 1)))
                    .replaceWithVoid()
                    .invoke(() -> LOG.infof("Recorded lockout for %s: reason=%s, expires=%s", key, reason, expiresAt));
        });
        return timeoutHelper.withTimeoutSilent(operation, "recordLockout");
    }

    @Override
    public Uni<Boolean> isLockedOut(String key) {
        final var lockoutKey = LOCKOUT_PREFIX + key;
        var operation = keyCommands.exists(lockoutKey);
        // Fail-open: return false on timeout (not locked out = allow)
        return timeoutHelper.withTimeoutFallback(operation, "isLockedOut", () -> false);
    }

    @Override
    public Uni<Instant> getLockoutExpiry(String key) {
        final var lockoutKey = LOCKOUT_PREFIX + key;
        var operation = hashCommands.hget(lockoutKey, FIELD_EXPIRES_AT).map(value -> {
            if (value == null) {
                return null;
            }
            return Instant.ofEpochMilli(Long.parseLong(value));
        });
        // Fail-open: return null on timeout (no expiry = allow)
        return timeoutHelper.withTimeoutFallback(operation, "getLockoutExpiry", () -> null);
    }

    @Override
    public Uni<Void> clearLockout(String key) {
        final var lockoutKey = LOCKOUT_PREFIX + key;
        var operation =
                keyCommands.del(lockoutKey).replaceWithVoid().invoke(() -> LOG.infof("Cleared lockout for %s", key));
        return timeoutHelper.withTimeoutSilent(operation, "clearLockout");
    }

    @Override
    public Uni<Integer> getLockoutCount(String key) {
        final var countKey = LOCKOUT_COUNT_PREFIX + key;
        var operation = valueCommands.get(countKey).map(value -> value != null ? Integer.parseInt(value) : 0);
        // Fail-open: return 0 on timeout
        return timeoutHelper.withTimeoutFallback(operation, "getLockoutCount", () -> 0);
    }

    @Override
    public Multi<LockoutInfo> streamAllLockouts() {
        final var args = new KeyScanArgs().match(LOCKOUT_PREFIX + "*").count(1000);
        return keyCommands
                .scan(args)
                .toMulti()
                .onItem()
                .transformToUniAndMerge(this::loadLockoutInfo)
                .select()
                .where(info -> info != null);
    }

    private Uni<LockoutInfo> loadLockoutInfo(String redisKey) {
        final var key = redisKey.substring(LOCKOUT_PREFIX.length());
        return hashCommands.hgetall(redisKey).flatMap(fields -> {
            if (fields == null || fields.isEmpty()) {
                return Uni.createFrom().nullItem();
            }

            final var lockedAtStr = fields.get(FIELD_LOCKED_AT);
            final var expiresAtStr = fields.get(FIELD_EXPIRES_AT);
            final var reason = fields.get(FIELD_REASON);
            final var failedAttemptsStr = fields.get(FIELD_FAILED_ATTEMPTS);

            if (lockedAtStr == null || expiresAtStr == null) {
                return Uni.createFrom().nullItem();
            }

            final var lockedAt = Instant.ofEpochMilli(Long.parseLong(lockedAtStr));
            final var expiresAt = Instant.ofEpochMilli(Long.parseLong(expiresAtStr));
            final var failedAttempts = failedAttemptsStr != null ? Integer.parseInt(failedAttemptsStr) : 0;

            // Check if still valid
            if (Instant.now().isAfter(expiresAt)) {
                return Uni.createFrom().nullItem();
            }

            return getLockoutCount(key)
                    .map(lockoutCount ->
                            new LockoutInfo(key, lockedAt, expiresAt, reason, failedAttempts, lockoutCount));
        });
    }
}
