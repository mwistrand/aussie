package aussie.adapter.out.ratelimit.redis;

import java.time.Instant;
import java.util.List;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.ratelimit.BucketState;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
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
 *   <li>Automatic key expiration based on window duration</li>
 *   <li>Shared state across all gateway instances</li>
 *   <li>Graceful degradation on Redis failures (allows requests)</li>
 * </ul>
 *
 * <p>Key format: {@code aussie:ratelimit:{type}:{serviceId}:{endpointId}:{clientId}}
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final Logger LOG = Logger.getLogger(RedisRateLimiter.class);

    /**
     * Lua script for atomic token bucket rate limiting.
     *
     * <p>Arguments:
     * <ol>
     *   <li>KEYS[1] - the rate limit key</li>
     *   <li>ARGV[1] - bucket capacity (max tokens)</li>
     *   <li>ARGV[2] - refill rate (tokens per second)</li>
     *   <li>ARGV[3] - current timestamp in milliseconds</li>
     *   <li>ARGV[4] - window duration in seconds (for TTL)</li>
     * </ol>
     *
     * <p>Returns array: [allowed (0/1), remaining, tokens_used, reset_at_epoch_seconds]
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local window_seconds = tonumber(ARGV[4])

            -- Get current state
            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])

            -- Initialize if new key
            if tokens == nil then
                tokens = capacity
                last_refill_ms = now_ms
            end

            -- Calculate token refill
            local elapsed_ms = now_ms - last_refill_ms
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
            local seconds_to_full = tokens_needed / refill_rate
            local reset_at = math.floor(now_ms / 1000) + math.ceil(seconds_to_full)

            -- Save state with TTL
            redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', now_ms)
            redis.call('EXPIRE', key, window_seconds * 2)

            -- Return: allowed, remaining, request_count (capacity - remaining), reset_at
            local remaining = math.floor(tokens)
            local request_count = math.floor(capacity - tokens)
            return {allowed, remaining, request_count, reset_at}
            """;

    /**
     * Lua script for getting rate limit status without consuming.
     */
    private static final String STATUS_SCRIPT =
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])

            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])

            if tokens == nil then
                return {1, capacity, 0, 0}
            end

            local elapsed_ms = now_ms - last_refill_ms
            local refill = (elapsed_ms / 1000.0) * refill_rate
            tokens = math.min(capacity, tokens + refill)

            local remaining = math.floor(tokens)
            local request_count = math.floor(capacity - tokens)
            local tokens_needed = capacity - tokens
            local seconds_to_full = tokens_needed / refill_rate
            local reset_at = math.floor(now_ms / 1000) + math.ceil(seconds_to_full)

            return {1, remaining, request_count, reset_at}
            """;

    private final ReactiveRedisDataSource redisDataSource;
    private final ReactiveKeyCommands<String> keyCommands;
    private final boolean enabled;

    public RedisRateLimiter(ReactiveRedisDataSource redisDataSource, boolean enabled) {
        this.redisDataSource = redisDataSource;
        this.keyCommands = redisDataSource.key(String.class);
        this.enabled = enabled;
    }

    @Override
    public Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var cacheKey = key.toCacheKey();
        final var capacity = limit.burstCapacity();
        final var refillRate = limit.refillRatePerSecond();
        final var nowMs = System.currentTimeMillis();
        final var windowSeconds = limit.windowSeconds();

        return executeTokenBucketScript(cacheKey, capacity, refillRate, nowMs, windowSeconds)
                .map(result -> parseDecision(result, limit))
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(error, "Redis rate limit check failed, allowing request");
                    return RateLimitDecision.allow();
                });
    }

    @Override
    public Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit) {
        if (!enabled) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var cacheKey = key.toCacheKey();
        final var capacity = limit.burstCapacity();
        final var refillRate = limit.refillRatePerSecond();
        final var nowMs = System.currentTimeMillis();

        return executeStatusScript(cacheKey, capacity, refillRate, nowMs)
                .map(result -> parseDecision(result, limit))
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(error, "Redis rate limit status check failed");
                    return RateLimitDecision.allow();
                });
    }

    @Override
    public Uni<Void> reset(RateLimitKey key) {
        final var cacheKey = key.toCacheKey();
        return keyCommands.del(cacheKey).replaceWithVoid();
    }

    @Override
    public Uni<Void> removeKeysMatching(String pattern) {
        // Use SCAN to find matching keys and delete them
        final var fullPattern = "*" + pattern + "*";

        return keyCommands
                .keys(fullPattern)
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    return keyCommands.del(keys.toArray(new String[0])).replaceWithVoid();
                })
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

    private Uni<List<Object>> executeTokenBucketScript(
            String key, long capacity, double refillRate, long nowMs, long windowSeconds) {

        // EVAL script numkeys key [key...] arg [arg...]
        return redisDataSource
                .execute(
                        "EVAL",
                        TOKEN_BUCKET_SCRIPT,
                        "1", // numkeys
                        key, // KEYS[1]
                        String.valueOf(capacity), // ARGV[1]
                        String.valueOf(refillRate), // ARGV[2]
                        String.valueOf(nowMs), // ARGV[3]
                        String.valueOf(windowSeconds) // ARGV[4]
                        )
                .map(this::parseArrayResponse);
    }

    private Uni<List<Object>> executeStatusScript(String key, long capacity, double refillRate, long nowMs) {

        // EVAL script numkeys key [key...] arg [arg...]
        return redisDataSource
                .execute(
                        "EVAL",
                        STATUS_SCRIPT,
                        "1", // numkeys
                        key, // KEYS[1]
                        String.valueOf(capacity), // ARGV[1]
                        String.valueOf(refillRate), // ARGV[2]
                        String.valueOf(nowMs) // ARGV[3]
                        )
                .map(this::parseArrayResponse);
    }

    private List<Object> parseArrayResponse(io.vertx.mutiny.redis.client.Response response) {
        if (response == null) {
            throw new IllegalStateException("Null response from Redis");
        }

        // Response should be an array with 4 elements: [allowed, remaining, request_count, reset_at]
        final var result = new java.util.ArrayList<Object>(4);
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
        final var resetAt = Instant.ofEpochSecond(resetAtEpochSeconds);

        if (allowed) {
            return RateLimitDecision.allow(
                    remaining,
                    limit.burstCapacity(),
                    limit.windowSeconds(),
                    resetAt,
                    requestCount,
                    new BucketState(remaining, System.currentTimeMillis()));
        } else {
            final var retryAfter =
                    Math.max(1, resetAtEpochSeconds - Instant.now().getEpochSecond());
            return RateLimitDecision.rejected(
                    limit.burstCapacity(),
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
