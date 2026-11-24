package aussie.adapter.out.ratelimit.redis;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.RateLimitingConfig.RateLimitFallbackBehavior;
import aussie.core.model.ratelimit.BucketState;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;

/**
 * Redis-based rate limiter implementation for distributed deployments.
 *
 * <p>Uses Redis Lua scripts for atomic rate limit operations, ensuring correct
 * behavior across multiple gateway instances.
 *
 * <p>Features:
 * <ul>
 *   <li>Token bucket algorithm implemented atomically in Lua</li>
 *   <li>Automatic key expiration when a bucket can refill</li>
 *   <li>Shared state across all gateway instances</li>
 *   <li>Configurable fail-closed, local-bucket, or fail-open behavior on Redis failures</li>
 * </ul>
 *
 * <p>Keys use the {@code aussie:ratelimit:} namespace and include a schema version and algorithm suffix.
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(RedisRateLimiter.class);
    private static final long MAX_EXACT_REDIS_NUMBER = 9_007_199_254_740_991L;
    private static final double MAX_SAFE_REDIS_SECONDS = MAX_EXACT_REDIS_NUMBER / 2.0;
    private static final EffectiveRateLimit LOCAL_EMERGENCY_LIMIT = new EffectiveRateLimit(1, 1, 1);
    private static final EffectiveRateLimit LOCAL_EMERGENCY_ZERO_LIMIT = new EffectiveRateLimit(0, 1, 0);

    /**
     * Lua script for atomic token bucket rate limiting.
     *
     * <p>Arguments:
     * <ol>
     *   <li>KEYS[1] - the rate limit key</li>
     *   <li>ARGV[1] - bucket capacity (max tokens)</li>
     *   <li>ARGV[2] - refill rate (tokens per second)</li>
     *   <li>ARGV[3] - window duration in seconds (for TTL)</li>
     *   <li>ARGV[4] - local Redis-recovery generation</li>
     * </ol>
     *
     * <p>Returns array: [allowed (0/1), remaining, tokens_used, reset_at_epoch_seconds, retry_after_seconds]
     */
    static final String TOKEN_BUCKET_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local window_seconds = tonumber(ARGV[3])
            local recovery_generation = ARGV[4]
            local redis_time = redis.call('TIME')
            local now_ms = (redis_time[1] * 1000) + math.floor(redis_time[2] / 1000)

            -- Get current state
            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms', 'recovery_generation')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])
            local stored_recovery_generation = data[3] or '0'

            -- Fence a bucket once during recovery so Redis cannot replay its old burst.
            if recovery_generation ~= '0' and recovery_generation ~= stored_recovery_generation then
                tokens = 0
                last_refill_ms = now_ms
            end

            -- Initialize if new key
            if tokens == nil then
                tokens = capacity
                last_refill_ms = now_ms
            end

            -- Calculate token refill
            local elapsed_ms = math.max(0, now_ms - last_refill_ms)
            local refill = (elapsed_ms / 1000.0) * refill_rate
            tokens = math.min(capacity, tokens + refill)

            -- Check if request is allowed
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            -- Calculate reset time (when bucket would be full again)
            local tokens_needed = capacity - tokens
            -- A zero-refill policy resets on its configured window.
            local seconds_to_full = refill_rate > 0 and (tokens_needed / refill_rate) or window_seconds
            local reset_after = math.max(1, math.ceil(seconds_to_full))
            local reset_at = math.floor(now_ms / 1000) + reset_after
            local retry_after = allowed == 1 and 0 or (refill_rate > 0 and math.max(1, math.ceil((1 - tokens) / refill_rate)) or window_seconds)

            -- Save state with TTL
            redis.call(
                    'HSET', key, 'tokens', tokens, 'last_refill_ms', now_ms,
                    'recovery_generation', recovery_generation ~= '0' and recovery_generation or stored_recovery_generation)
            redis.call('EXPIRE', key, reset_after)

            -- Return: allowed, remaining, request_count (capacity - remaining), reset_at
            local remaining = math.floor(tokens)
            local request_count = math.floor(capacity - tokens)
            return {allowed, remaining, request_count, reset_at, retry_after}
            """;

    /**
     * Lua script for getting rate limit status without consuming.
     */
    static final String STATUS_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local window_seconds = tonumber(ARGV[3])
            local recovery_generation = ARGV[4]
            local redis_time = redis.call('TIME')
            local now_ms = (redis_time[1] * 1000) + math.floor(redis_time[2] / 1000)

            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms', 'recovery_generation')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])
            local stored_recovery_generation = data[3] or '0'

            if tokens == nil then
                if recovery_generation ~= '0' then
                    return {1, 0, capacity, math.floor(now_ms / 1000) + window_seconds, window_seconds}
                end
                return {1, capacity, 0, math.floor(now_ms / 1000) + window_seconds, 0}
            end

            if recovery_generation ~= '0' and recovery_generation ~= stored_recovery_generation then
                return {1, 0, capacity, math.floor(now_ms / 1000) + window_seconds, window_seconds}
            end

            local elapsed_ms = math.max(0, now_ms - last_refill_ms)
            local refill = (elapsed_ms / 1000.0) * refill_rate
            tokens = math.min(capacity, tokens + refill)

            local remaining = math.floor(tokens)
            local request_count = math.floor(capacity - tokens)
            local tokens_needed = capacity - tokens
            local seconds_to_full = refill_rate > 0 and (tokens_needed / refill_rate) or window_seconds
            local reset_after = math.max(1, math.ceil(seconds_to_full))
            local reset_at = math.floor(now_ms / 1000) + reset_after

            return {1, remaining, request_count, reset_at, 0}
            """;

    private final ReactiveRedisDataSource redisDataSource;
    private final ReactiveKeyCommands<String> keyCommands;
    private final boolean enabled;
    private final RateLimiter fallback;
    private final RateLimitFallbackBehavior fallbackBehavior;
    private final Metrics metrics;
    private final AtomicBoolean fallbackClosed = new AtomicBoolean();
    private final AtomicReference<String> recoveryGeneration = new AtomicReference<>("0");
    private final Set<String> recoveryPendingKeys = ConcurrentHashMap.newKeySet();

    public RedisRateLimiter(ReactiveRedisDataSource redisDataSource, boolean enabled) {
        this(redisDataSource, enabled, null, RateLimitFallbackBehavior.DENY, null);
    }

    public RedisRateLimiter(
            ReactiveRedisDataSource redisDataSource,
            boolean enabled,
            RateLimiter fallback,
            RateLimitFallbackBehavior fallbackBehavior,
            Metrics metrics) {
        this.redisDataSource = redisDataSource;
        this.keyCommands = redisDataSource.key(String.class);
        this.enabled = enabled;
        this.fallback = fallback;
        this.fallbackBehavior = fallbackBehavior == null ? RateLimitFallbackBehavior.DENY : fallbackBehavior;
        this.metrics = metrics;
    }

    @Override
    public Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }
        validateLimit(limit);

        final var cacheKey = cacheKey(key);
        final var capacity = limit.burstCapacity();
        final var refillRate = limit.refillRatePerSecond();
        final var windowSeconds = limit.windowSeconds();
        final var generation = recoveryGenerationFor(cacheKey);

        return executeTokenBucketScript(cacheKey, capacity, refillRate, windowSeconds, generation)
                .map(result -> parseDecision(result, limit))
                .invoke(ignored -> markBucketRecovered(cacheKey, generation))
                .onFailure()
                .recoverWithUni(error -> handleBackendFailure(key, limit, error, true));
    }

    @Override
    public Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }
        validateLimit(limit);

        final var cacheKey = cacheKey(key);
        final var capacity = limit.burstCapacity();
        final var refillRate = limit.refillRatePerSecond();
        final var generation = recoveryGenerationFor(cacheKey);

        return executeStatusScript(cacheKey, capacity, refillRate, limit.windowSeconds(), generation)
                .map(result -> parseDecision(result, limit))
                .onFailure()
                .recoverWithUni(error -> handleBackendFailure(key, limit, error, false));
    }

    /**
     * Apply the configured fallback strategy when the Redis backend is unavailable.
     *
     * <p>Behavior is controlled by {@link RateLimitFallbackBehavior}:
     * <ul>
     *   <li>{@code LOCAL_BUCKET} - delegate to the in-process limiter with a one-request/one-second
     *       emergency limit; if no fallback bucket is wired, fail closed.</li>
     *   <li>{@code DENY} - reject the request (fail closed).</li>
     *   <li>{@code ALLOW} - permit the request (fail open; legacy/dev behavior).</li>
     * </ul>
     *
     * @param consume {@code true} for {@link #checkAndConsume}, {@code false} for {@link #getStatus}
     */
    private Uni<RateLimitDecision> handleBackendFailure(
            RateLimitKey key, EffectiveRateLimit limit, Throwable error, boolean consume) {
        final var serviceId = key.serviceId();
        return switch (fallbackBehavior) {
            case LOCAL_BUCKET -> {
                if (fallback != null) {
                    markBucketUnavailable(cacheKey(key));
                    LOG.warnv(
                            error,
                            "Redis rate limit unavailable; routing through local fallback bucket for service={0}",
                            serviceId);
                    recordFallback(serviceId, "local-bucket");
                    final var fallbackLimit = localFallbackLimit(limit);
                    yield consume
                            ? fallback.checkAndConsume(key, fallbackLimit)
                            : fallback.getStatus(key, fallbackLimit);
                }
                LOG.warnv(
                        error,
                        "Redis rate limit unavailable and no fallback bucket wired; failing closed for service={0}",
                        serviceId);
                recordFallback(serviceId, "deny");
                yield Uni.createFrom().item(buildDenyDecision(limit));
            }
            case DENY -> {
                LOG.warnv(error, "Redis rate limit unavailable; failing closed for service={0}", serviceId);
                recordFallback(serviceId, "deny");
                yield Uni.createFrom().item(buildDenyDecision(limit));
            }
            case ALLOW -> {
                LOG.warnv(
                        error,
                        "Redis rate limit unavailable; failing open (allow) for service={0}. "
                                + "This is the legacy behavior; switch to LOCAL_BUCKET or DENY in production.",
                        serviceId);
                recordFallback(serviceId, "allow");
                yield Uni.createFrom().item(RateLimitDecision.allow());
            }
        };
    }

    /**
     * Record a fallback activation if a metrics sink is wired; otherwise no-op.
     */
    private void recordFallback(String serviceId, String mode) {
        if (metrics != null) {
            metrics.recordRateLimitFallback(serviceId, mode);
        }
    }

    private String recoveryGenerationFor(String cacheKey) {
        return recoveryPendingKeys.contains(cacheKey) ? recoveryGeneration.get() : "0";
    }

    private synchronized void markBucketUnavailable(String cacheKey) {
        if (recoveryPendingKeys.isEmpty()) {
            recoveryGeneration.set(UUID.randomUUID().toString());
        }
        recoveryPendingKeys.add(cacheKey);
    }

    private synchronized void markBucketRecovered(String cacheKey, String generation) {
        if ("0".equals(generation) || !generation.equals(recoveryGeneration.get())) {
            return;
        }
        recoveryPendingKeys.remove(cacheKey);
        if (recoveryPendingKeys.isEmpty()) {
            recoveryGeneration.set("0");
        }
    }

    private static EffectiveRateLimit localFallbackLimit(EffectiveRateLimit limit) {
        return limit.requestsPerWindow() == 0 || limit.burstCapacity() == 0
                ? LOCAL_EMERGENCY_ZERO_LIMIT
                : LOCAL_EMERGENCY_LIMIT;
    }

    /**
     * Build a rejection decision with reset timing derived from the limit window.
     * Used when the Redis backend is unavailable and the configured fallback is to fail closed.
     */
    private RateLimitDecision buildDenyDecision(EffectiveRateLimit limit) {
        final var resetAt = Instant.now().plusSeconds(limit.windowSeconds());
        return RateLimitDecision.rejected(
                limit.requestsPerWindow(),
                limit.windowSeconds(),
                resetAt,
                limit.windowSeconds(),
                (int) limit.burstCapacity(),
                new BucketState(0, System.currentTimeMillis()));
    }

    @Override
    public Uni<Void> reset(RateLimitKey key) {
        final var cacheKey = cacheKey(key);
        return keyCommands.del(cacheKey).replaceWithVoid();
    }

    @Override
    public Uni<Void> removeKeysMatching(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > 128 || pattern.matches(".*[\\*?\\[\\]].*")) {
            return Uni.createFrom().failure(new IllegalArgumentException("Unsafe rate-limit key pattern"));
        }
        final var fullPattern = "*" + pattern + "*";
        return keyCommands
                .scan(new KeyScanArgs().match(fullPattern).count(100))
                .toMulti()
                .onItem()
                .transformToUniAndConcatenate(key -> keyCommands.del(key).replaceWithVoid())
                .collect()
                .last()
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(error, "Failed to remove keys matching pattern: {0}", pattern);
                    return null;
                });
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (fallback != null && fallbackClosed.compareAndSet(false, true)) {
            fallback.close();
        }
    }

    private String cacheKey(RateLimitKey key) {
        return key.toCacheKey() + ":v1:bucket";
    }

    private void validateLimit(EffectiveRateLimit limit) {
        if (limit.requestsPerWindow() > MAX_EXACT_REDIS_NUMBER
                || limit.burstCapacity() > MAX_EXACT_REDIS_NUMBER
                || limit.windowSeconds() > MAX_SAFE_REDIS_SECONDS
                || fullRefillSeconds(limit) > MAX_SAFE_REDIS_SECONDS) {
            throw new IllegalArgumentException("Rate limit exceeds Redis numeric bounds");
        }
    }

    private double fullRefillSeconds(EffectiveRateLimit limit) {
        if (limit.requestsPerWindow() == 0) {
            return limit.windowSeconds();
        }
        return ((double) limit.burstCapacity() * limit.windowSeconds()) / limit.requestsPerWindow();
    }

    private Uni<List<Object>> executeTokenBucketScript(
            String key, long capacity, double refillRate, long windowSeconds, String generation) {

        // EVAL script numkeys key [key...] arg [arg...]
        return redisDataSource
                .execute(
                        "EVAL",
                        TOKEN_BUCKET_SCRIPT,
                        "1", // numkeys
                        key, // KEYS[1]
                        String.valueOf(capacity), // ARGV[1]
                        String.valueOf(refillRate), // ARGV[2]
                        String.valueOf(windowSeconds), // ARGV[3]
                        String.valueOf(generation) // ARGV[4]
                        )
                .map(this::parseArrayResponse);
    }

    private Uni<List<Object>> executeStatusScript(
            String key, long capacity, double refillRate, long windowSeconds, String generation) {

        // EVAL script numkeys key [key...] arg [arg...]
        return redisDataSource
                .execute(
                        "EVAL",
                        STATUS_SCRIPT,
                        "1", // numkeys
                        key, // KEYS[1]
                        String.valueOf(capacity), // ARGV[1]
                        String.valueOf(refillRate), // ARGV[2]
                        String.valueOf(windowSeconds), // ARGV[3]
                        String.valueOf(generation) // ARGV[4]
                        )
                .map(this::parseArrayResponse);
    }

    private List<Object> parseArrayResponse(io.vertx.mutiny.redis.client.Response response) {
        if (response == null) {
            throw new IllegalStateException("Null response from Redis");
        }

        final var result = new java.util.ArrayList<Object>(5);
        for (var i = 0; i < response.size(); i++) {
            result.add(response.get(i).toLong());
        }
        return result;
    }

    private RateLimitDecision parseDecision(List<Object> result, EffectiveRateLimit limit) {
        final var allowed = toLong(result.get(0)) == 1;
        final var remaining = toLong(result.get(1));
        final var requestCount = (int) toLong(result.get(2));
        final var resetAtEpochSeconds = toLong(result.get(3));
        final var retryAfter = toLong(result.get(4));
        final var resetAt = Instant.ofEpochSecond(resetAtEpochSeconds);

        if (allowed) {
            return RateLimitDecision.allow(
                    remaining,
                    limit.requestsPerWindow(),
                    limit.windowSeconds(),
                    resetAt,
                    requestCount,
                    new BucketState(remaining, System.currentTimeMillis()));
        } else {
            return RateLimitDecision.rejected(
                    limit.requestsPerWindow(),
                    limit.windowSeconds(),
                    resetAt,
                    retryAfter,
                    requestCount,
                    new BucketState(0, System.currentTimeMillis()));
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        throw new IllegalArgumentException("Cannot convert to long: " + value);
    }
}
