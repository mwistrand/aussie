package aussie.adapter.out.storage.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

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
 *   <li>Failed attempts: {@code aussie:auth:failed:{base64url(key)}}</li>
 *   <li>Lockout: {@code aussie:auth:lockout:{base64url(key)}} (hash with lockedAt, expiresAt, reason, failedAttempts)</li>
 *   <li>Lockout count: {@code aussie:auth:lockout-count:{base64url(key)}}</li>
 * </ul>
 * The braces are Redis Cluster hash tags, keeping every atomic transition for
 * one identity in the same slot.
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
    static final String RECORD_ATTEMPT_SCRIPT =
            """
            local initialized = redis.call('EXISTS', KEYS[1]) == 0
            if initialized then redis.call('SET', KEYS[1], ARGV[2]) end
            local count = redis.call('INCR', KEYS[1])
            if initialized then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """;
    static final String RECORD_LOCKOUT_SCRIPT =
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return tonumber(redis.call('GET', KEYS[2])) or 0
            end
            local redis_time = redis.call('TIME')
            local now_ms = (redis_time[1] * 1000) + math.floor(redis_time[2] / 1000)
            local expires_at = now_ms + (tonumber(ARGV[1]) * 1000)
            local failed = tonumber(redis.call('GET', KEYS[3])) or 0
            redis.call('HSET', KEYS[1],
                'lockedAt', now_ms,
                'expiresAt', expires_at,
                'reason', ARGV[2],
                'failedAttempts', failed)
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            local count_initialized = redis.call('EXISTS', KEYS[2]) == 0
            if count_initialized then redis.call('SET', KEYS[2], ARGV[4]) end
            local lockouts = redis.call('INCR', KEYS[2])
            if count_initialized then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
            redis.call('DEL', KEYS[3])
            return lockouts
            """;

    private final ReactiveRedisDataSource redisDataSource;
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
        this.redisDataSource = redisDataSource;
        this.timeoutHelper =
                new RedisTimeoutHelper(resiliencyConfig.redis().operationTimeout(), metrics, "FailedAttemptRepository");
        LOG.info("Initialized Redis failed attempt repository");
    }

    @Override
    public Uni<Long> recordFailedAttempt(String key, Duration windowDuration) {
        final var redisKey = redisKey(FAILED_PREFIX, key);
        final var ttlSeconds = windowDuration.toSeconds();
        validateTtl(ttlSeconds);

        var operation = getValueWithLegacyFallback(FAILED_PREFIX, key)
                .map(value -> value != null ? Long.parseLong(value) : 0L)
                .flatMap(initialCount -> redisDataSource.execute(
                        "EVAL",
                        RECORD_ATTEMPT_SCRIPT,
                        "1",
                        redisKey,
                        String.valueOf(ttlSeconds),
                        String.valueOf(initialCount)))
                .map(response -> response.toLong())
                .invoke(count -> LOG.debugf("Recorded failed attempt for %s: count=%d", key, count));
        return timeoutHelper.withTimeout(operation, "recordFailedAttempt");
    }

    @Override
    public Uni<Long> getFailedAttemptCount(String key) {
        var operation =
                getValueWithLegacyFallback(FAILED_PREFIX, key).map(value -> value != null ? Long.parseLong(value) : 0L);
        return timeoutHelper.withTimeout(operation, "getFailedAttemptCount");
    }

    @Override
    public Uni<Void> clearFailedAttempts(String key) {
        var operation = deleteCurrentAndLegacy(FAILED_PREFIX, key)
                .invoke(() -> LOG.debugf("Cleared failed attempts for %s", key));
        return timeoutHelper.withTimeout(operation, "clearFailedAttempts");
    }

    @Override
    public Uni<Void> recordLockout(String key, Duration lockoutDuration, String reason) {
        final var lockoutKey = redisKey(LOCKOUT_PREFIX, key);
        final var countKey = redisKey(LOCKOUT_COUNT_PREFIX, key);
        final var ttlSeconds = lockoutDuration.toSeconds();
        final var storedReason = reason != null ? reason : "max_failed_attempts";
        validateTtl(ttlSeconds);
        if (storedReason.length() > 256) {
            throw new IllegalArgumentException("Lockout reason exceeds 256 characters");
        }
        final var operation = getValueWithLegacyFallback(LOCKOUT_COUNT_PREFIX, key)
                .map(value -> value != null ? Integer.parseInt(value) : 0)
                .flatMap(initialCount -> redisDataSource.execute(
                        "EVAL",
                        RECORD_LOCKOUT_SCRIPT,
                        "3",
                        lockoutKey,
                        countKey,
                        redisKey(FAILED_PREFIX, key),
                        String.valueOf(ttlSeconds),
                        storedReason,
                        String.valueOf(Duration.ofDays(30).toSeconds()),
                        String.valueOf(initialCount)))
                .call(() -> keyCommands.del(legacyKey(FAILED_PREFIX, key)))
                .replaceWithVoid()
                .invoke(() -> LOG.infof("Recorded lockout for %s: reason=%s", key, reason));
        return timeoutHelper.withTimeout(operation, "recordLockout");
    }

    @Override
    public Uni<Boolean> isLockedOut(String key) {
        final var lockoutKey = redisKey(LOCKOUT_PREFIX, key);
        var operation = keyCommands
                .exists(lockoutKey)
                .flatMap(exists ->
                        exists ? Uni.createFrom().item(true) : keyCommands.exists(legacyKey(LOCKOUT_PREFIX, key)));
        return timeoutHelper.withTimeout(operation, "isLockedOut");
    }

    @Override
    public Uni<Instant> getLockoutExpiry(String key) {
        final var currentKey = redisKey(LOCKOUT_PREFIX, key);
        var operation = keyCommands.ttl(currentKey).flatMap(ttl -> {
            if (ttl >= 0) {
                return Uni.createFrom().item(Instant.now().plusSeconds(ttl));
            }
            return getHashValueWithLegacyFallback(LOCKOUT_PREFIX, key, FIELD_EXPIRES_AT)
                    .map(value -> value != null ? Instant.ofEpochMilli(Long.parseLong(value)) : null);
        });
        return timeoutHelper.withTimeout(operation, "getLockoutExpiry");
    }

    @Override
    public Uni<Void> clearLockout(String key) {
        var operation =
                deleteCurrentAndLegacy(LOCKOUT_PREFIX, key).invoke(() -> LOG.infof("Cleared lockout for %s", key));
        return timeoutHelper.withTimeout(operation, "clearLockout");
    }

    @Override
    public Uni<Integer> getLockoutCount(String key) {
        var operation = getValueWithLegacyFallback(LOCKOUT_COUNT_PREFIX, key)
                .map(value -> value != null ? Integer.parseInt(value) : 0);
        return timeoutHelper.withTimeout(operation, "getLockoutCount");
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
        final var storedKey = redisKey.substring(LOCKOUT_PREFIX.length());
        final var legacy = !storedKey.startsWith("{") || !storedKey.endsWith("}");
        final String key;
        try {
            key = legacy
                    ? storedKey
                    : new String(
                            Base64.getUrlDecoder().decode(storedKey.substring(1, storedKey.length() - 1)),
                            StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            LOG.warnv(error, "Ignoring malformed authentication lockout key: {0}", redisKey);
            return Uni.createFrom().nullItem();
        }
        final Uni<LockoutInfo> operation = hashCommands.hgetall(redisKey).flatMap(fields -> {
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

            // Legacy timestamps came from gateway clocks; current keys expire against Redis time.
            if (legacy && Instant.now().isAfter(expiresAt)) {
                return Uni.createFrom().nullItem();
            }

            return getLockoutCount(key)
                    .map(lockoutCount ->
                            new LockoutInfo(key, lockedAt, expiresAt, reason, failedAttempts, lockoutCount));
        });
        if (!legacy) {
            return operation;
        }
        return keyCommands
                .exists(redisKey(LOCKOUT_PREFIX, key))
                .flatMap(currentExists -> currentExists ? Uni.createFrom().nullItem() : operation);
    }

    private String redisKey(String prefix, String key) {
        if (key == null || key.isBlank() || key.length() > 256) {
            throw new IllegalArgumentException("Invalid authentication rate-limit key");
        }
        final var encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return prefix + "{" + encoded + "}";
    }

    private String legacyKey(String prefix, String key) {
        return prefix + key;
    }

    private Uni<String> getValueWithLegacyFallback(String prefix, String key) {
        return valueCommands
                .get(redisKey(prefix, key))
                .flatMap(value ->
                        value != null ? Uni.createFrom().item(value) : valueCommands.get(legacyKey(prefix, key)));
    }

    private Uni<String> getHashValueWithLegacyFallback(String prefix, String key, String field) {
        return hashCommands
                .hget(redisKey(prefix, key), field)
                .flatMap(value -> value != null
                        ? Uni.createFrom().item(value)
                        : hashCommands.hget(legacyKey(prefix, key), field));
    }

    private Uni<Void> deleteCurrentAndLegacy(String prefix, String key) {
        return keyCommands
                .del(redisKey(prefix, key))
                .call(() -> keyCommands.del(legacyKey(prefix, key)))
                .replaceWithVoid();
    }

    private void validateTtl(long ttlSeconds) {
        if (ttlSeconds <= 0 || ttlSeconds > Duration.ofDays(365).toSeconds()) {
            throw new IllegalArgumentException("Redis TTL must be between 1 second and 365 days");
        }
    }
}
