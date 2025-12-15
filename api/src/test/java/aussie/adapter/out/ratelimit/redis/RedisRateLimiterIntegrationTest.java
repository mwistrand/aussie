package aussie.adapter.out.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.redis.client.Response;
import io.vertx.redis.client.RedisOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import aussie.core.model.ratelimit.RateLimitKey;

/**
 * Integration tests for Redis rate limiting Lua scripts using a real Redis instance via testcontainers.
 *
 * <p>These tests validate the atomic Lua scripts that implement the token bucket algorithm
 * in Redis, ensuring correct behavior for distributed rate limiting.
 */
@Testcontainers
@DisplayName("Redis Rate Limiter Integration")
class RedisRateLimiterIntegrationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static Vertx vertx;
    private static Redis redisClient;

    /**
     * Lua script for atomic token bucket rate limiting.
     * This is the same script used in RedisRateLimiter.
     */
    private static final String TOKEN_BUCKET_SCRIPT =
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local window_seconds = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens = tonumber(data[1])
            local last_refill_ms = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last_refill_ms = now_ms
            end

            local elapsed_ms = now_ms - last_refill_ms
            local refill = (elapsed_ms / 1000.0) * refill_rate
            tokens = math.min(capacity, tokens + refill)

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            local tokens_needed = capacity - tokens
            local seconds_to_full = tokens_needed / refill_rate
            local reset_at = math.floor(now_ms / 1000) + math.ceil(seconds_to_full)

            redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', now_ms)
            redis.call('EXPIRE', key, window_seconds * 2)

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

    @BeforeAll
    static void setUpClass() {
        vertx = Vertx.vertx();

        var redisOptions =
                new RedisOptions().setConnectionString("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

        redisClient = Redis.createClient(vertx, redisOptions);
    }

    @AfterAll
    static void tearDownClass() {
        if (redisClient != null) {
            redisClient.close();
        }
        if (vertx != null) {
            vertx.closeAndAwait();
        }
    }

    @BeforeEach
    void setUp() {
        // Clear all keys before each test
        RedisAPI.api(redisClient).flushall(List.of()).await().atMost(Duration.ofSeconds(5));
    }

    private Response executeTokenBucket(String key, long capacity, double refillRate, long nowMs, long windowSeconds) {
        return redisClient
                .send(Request.cmd(Command.EVAL)
                        .arg(TOKEN_BUCKET_SCRIPT)
                        .arg("1") // numkeys
                        .arg(key) // KEYS[1]
                        .arg(String.valueOf(capacity)) // ARGV[1]
                        .arg(String.valueOf(refillRate)) // ARGV[2]
                        .arg(String.valueOf(nowMs)) // ARGV[3]
                        .arg(String.valueOf(windowSeconds))) // ARGV[4]
                .await()
                .atMost(Duration.ofSeconds(5));
    }

    private Response executeStatus(String key, long capacity, double refillRate, long nowMs) {
        return redisClient
                .send(Request.cmd(Command.EVAL)
                        .arg(STATUS_SCRIPT)
                        .arg("1") // numkeys
                        .arg(key) // KEYS[1]
                        .arg(String.valueOf(capacity)) // ARGV[1]
                        .arg(String.valueOf(refillRate)) // ARGV[2]
                        .arg(String.valueOf(nowMs))) // ARGV[3]
                .await()
                .atMost(Duration.ofSeconds(5));
    }

    private boolean isAllowed(Response response) {
        return response.get(0).toLong() == 1;
    }

    private long getRemaining(Response response) {
        return response.get(1).toLong();
    }

    private long getRequestCount(Response response) {
        return response.get(2).toLong();
    }

    private long getResetAt(Response response) {
        return response.get(3).toLong();
    }

    @Nested
    @DisplayName("Basic token bucket operations")
    class BasicOperationTests {

        @Test
        @DisplayName("should allow requests when limit not exceeded")
        void shouldAllowRequestsWhenLimitNotExceeded() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            var response = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
            assertEquals(9, getRemaining(response));
        }

        @Test
        @DisplayName("should reject requests when limit exceeded")
        void shouldRejectRequestsWhenLimitExceeded() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            // Exhaust the bucket
            for (int i = 0; i < 3; i++) {
                var response = executeTokenBucket(key, 3, 100.0 / 60.0, nowMs, 60);
                assertTrue(isAllowed(response), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be rejected
            var rejected = executeTokenBucket(key, 3, 100.0 / 60.0, nowMs, 60);

            assertFalse(isAllowed(rejected));
            assertEquals(0, getRemaining(rejected));
        }

        @Test
        @DisplayName("should track separate buckets per key")
        void shouldTrackSeparateBucketsPerKey() {
            var key1 = "test:client-1:service-1";
            var key2 = "test:client-2:service-1";
            var nowMs = System.currentTimeMillis();

            // Client 1 makes 5 requests
            for (int i = 0; i < 5; i++) {
                executeTokenBucket(key1, 5, 100.0 / 60.0, nowMs, 60);
            }

            // Client 2 should still have full capacity
            var response = executeTokenBucket(key2, 5, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
            assertEquals(4, getRemaining(response));
        }

        @Test
        @DisplayName("should consume exactly one token per request")
        void shouldConsumeExactlyOneToken() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            var r1 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);
            var r2 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);
            var r3 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);

            assertEquals(9, getRemaining(r1));
            assertEquals(8, getRemaining(r2));
            assertEquals(7, getRemaining(r3));
        }
    }

    @Nested
    @DisplayName("Token refill")
    class TokenRefillTests {

        @Test
        @DisplayName("should refill tokens based on elapsed time")
        void shouldRefillTokensBasedOnElapsedTime() {
            var key = "test:client-1:service-1";
            var startMs = 1000000L;
            var capacity = 100L;
            var refillRate = 100.0 / 60.0; // ~1.67 tokens per second

            // First request at startMs - initializes bucket with capacity
            var response = executeTokenBucket(key, capacity, refillRate, startMs, 60);
            assertEquals(99, getRemaining(response)); // 100 - 1 = 99

            // After 60 seconds, should have refilled back to capacity
            var afterRefillMs = startMs + 60000;
            var afterRefill = executeTokenBucket(key, capacity, refillRate, afterRefillMs, 60);

            assertTrue(isAllowed(afterRefill));
            // Should be back to capacity - 1 = 99
            assertEquals(99, getRemaining(afterRefill));
        }

        @Test
        @DisplayName("should not exceed capacity when refilling")
        void shouldNotExceedCapacityWhenRefilling() {
            var key = "test:client-1:service-1";
            var startMs = 1000000L;
            var capacity = 50L;
            var refillRate = 100.0 / 60.0;

            // Initialize bucket
            executeTokenBucket(key, capacity, refillRate, startMs, 60);

            // After a long time, tokens should be capped at capacity
            var afterLongTimeMs = startMs + 120000; // 2 minutes later
            var response = executeTokenBucket(key, capacity, refillRate, afterLongTimeMs, 60);

            assertTrue(isAllowed(response));
            // Should be capped at 50 (capacity) - 1 consumed = 49
            assertEquals(49, getRemaining(response));
        }

        @Test
        @DisplayName("should refill tokens over time after exhaustion")
        void shouldRefillTokensOverTimeAfterExhaustion() {
            var key = "test:client-1:service-1";
            var startMs = 1000000L;
            var capacity = 5L;
            var refillRate = 10.0; // 10 tokens per second

            // Exhaust all tokens
            for (int i = 0; i < 5; i++) {
                var response = executeTokenBucket(key, capacity, refillRate, startMs, 60);
                assertTrue(isAllowed(response));
            }

            // Should be rejected now
            var rejected = executeTokenBucket(key, capacity, refillRate, startMs, 60);
            assertFalse(isAllowed(rejected));

            // After 1 second, should have refilled to capacity (10 tokens at 10/sec, capped at 5)
            var afterRefillMs = startMs + 1000;
            var afterRefill = executeTokenBucket(key, capacity, refillRate, afterRefillMs, 60);

            assertTrue(isAllowed(afterRefill));
            assertEquals(4, getRemaining(afterRefill)); // 5 refilled - 1 consumed = 4
        }
    }

    @Nested
    @DisplayName("Status check (non-consuming)")
    class StatusCheckTests {

        @Test
        @DisplayName("should return current status without consuming")
        void shouldReturnStatusWithoutConsuming() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            // Make 3 requests
            for (int i = 0; i < 3; i++) {
                executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);
            }

            // Check status multiple times - should not consume
            var status1 = executeStatus(key, 10, 100.0 / 60.0, nowMs);
            var status2 = executeStatus(key, 10, 100.0 / 60.0, nowMs);

            assertEquals(getRemaining(status1), getRemaining(status2));
            assertEquals(7, getRemaining(status1)); // 10 - 3 consumed
        }

        @Test
        @DisplayName("should return full capacity for unknown key")
        void shouldReturnFullCapacityForUnknownKey() {
            var key = "test:new-client:new-service";
            var nowMs = System.currentTimeMillis();

            var status = executeStatus(key, 10, 100.0 / 60.0, nowMs);

            assertTrue(isAllowed(status));
            assertEquals(10, getRemaining(status)); // Full capacity for new key
        }
    }

    @Nested
    @DisplayName("Reset and delete operations")
    class ResetOperationTests {

        @Test
        @DisplayName("should reset bucket when key deleted")
        void shouldResetBucketWhenKeyDeleted() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            // Exhaust the bucket
            for (int i = 0; i < 5; i++) {
                executeTokenBucket(key, 5, 100.0 / 60.0, nowMs, 60);
            }

            // Delete the key
            RedisAPI.api(redisClient).del(List.of(key)).await().atMost(Duration.ofSeconds(5));

            // Should have full capacity again (new bucket created)
            var response = executeTokenBucket(key, 5, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
            assertEquals(4, getRemaining(response)); // 5 - 1 = 4
        }
    }

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("should handle concurrent requests atomically")
        void shouldHandleConcurrentRequestsAtomically() throws InterruptedException {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();
            var capacity = 100L;

            var threadCount = 10;
            var requestsPerThread = 10;
            var latch = new CountDownLatch(threadCount);
            var allowedCount = new AtomicInteger(0);
            var rejectedCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int r = 0; r < requestsPerThread; r++) {
                            var response = executeTokenBucket(key, capacity, 100.0 / 60.0, nowMs, 60);
                            if (isAllowed(response)) {
                                allowedCount.incrementAndGet();
                            } else {
                                rejectedCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Total should equal all requests
            assertEquals(threadCount * requestsPerThread, allowedCount.get() + rejectedCount.get());
            // With 100 capacity, we should have exactly 100 allowed
            assertEquals(100, allowedCount.get());
        }

        @Test
        @DisplayName("should isolate concurrent requests to different keys")
        void shouldIsolateConcurrentRequestsToDifferentKeys() throws InterruptedException {
            var nowMs = System.currentTimeMillis();
            var capacity = 10L;

            var threadCount = 5;
            var latch = new CountDownLatch(threadCount);
            var results = new ArrayList<Long>();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final var key = "test:client-" + t + ":service";
                executor.submit(() -> {
                    try {
                        var response = executeTokenBucket(key, capacity, 100.0 / 60.0, nowMs, 60);
                        synchronized (results) {
                            results.add(getRemaining(response));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // Each client should have 9 remaining (10 - 1)
            assertEquals(5, results.size());
            for (var remaining : results) {
                assertEquals(9, remaining, "Each client should have independent bucket");
            }
        }
    }

    @Nested
    @DisplayName("Decision metadata")
    class DecisionMetadataTests {

        @Test
        @DisplayName("should include correct request count")
        void shouldIncludeCorrectRequestCount() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            var r1 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);
            var r2 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);
            var r3 = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);

            assertEquals(1, getRequestCount(r1));
            assertEquals(2, getRequestCount(r2));
            assertEquals(3, getRequestCount(r3));
        }

        @Test
        @DisplayName("should include reset time in future")
        void shouldIncludeResetTimeInFuture() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();
            var nowSeconds = nowMs / 1000;

            var response = executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);

            var resetAt = getResetAt(response);
            assertTrue(resetAt > nowSeconds, "Reset time should be in the future");
        }
    }

    @Nested
    @DisplayName("Key expiration")
    class KeyExpirationTests {

        @Test
        @DisplayName("should set TTL on keys")
        void shouldSetTtlOnKeys() {
            var key = "test:client-1:service-1";
            var nowMs = System.currentTimeMillis();

            executeTokenBucket(key, 10, 100.0 / 60.0, nowMs, 60);

            // Check TTL
            var ttl = RedisAPI.api(redisClient).ttl(key).await().atMost(Duration.ofSeconds(5));

            // TTL should be set (window_seconds * 2 = 120)
            assertTrue(ttl.toLong() > 0, "TTL should be set");
            assertTrue(ttl.toLong() <= 120, "TTL should not exceed 2 * window_seconds");
        }
    }

    @Nested
    @DisplayName("Rate limit key format")
    class KeyFormatTests {

        @Test
        @DisplayName("HTTP keys should be tracked separately")
        void httpKeysShouldBeTrackedSeparately() {
            var key1 = RateLimitKey.http("client-1", "service-1", "/api/users").toCacheKey();
            var key2 = RateLimitKey.http("client-1", "service-1", "/api/posts").toCacheKey();
            var nowMs = System.currentTimeMillis();

            // Exhaust key1
            for (int i = 0; i < 5; i++) {
                executeTokenBucket(key1, 5, 100.0 / 60.0, nowMs, 60);
            }

            // key2 should still be available
            var response = executeTokenBucket(key2, 5, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
        }

        @Test
        @DisplayName("WebSocket keys should be tracked separately from HTTP")
        void wsKeysShouldBeTrackedSeparatelyFromHttp() {
            var httpKey = RateLimitKey.http("client", "service", null).toCacheKey();
            var wsKey = RateLimitKey.wsConnection("client", "service").toCacheKey();
            var nowMs = System.currentTimeMillis();

            // Exhaust HTTP bucket
            for (int i = 0; i < 5; i++) {
                executeTokenBucket(httpKey, 5, 100.0 / 60.0, nowMs, 60);
            }

            // WS should still be available
            var response = executeTokenBucket(wsKey, 5, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
        }

        @Test
        @DisplayName("WS_MESSAGE keys should track per connection")
        void wsMessageKeysShouldTrackPerConnection() {
            var key1 = RateLimitKey.wsMessage("client", "service", "conn-1").toCacheKey();
            var key2 = RateLimitKey.wsMessage("client", "service", "conn-2").toCacheKey();
            var nowMs = System.currentTimeMillis();

            // Exhaust conn-1 bucket
            for (int i = 0; i < 5; i++) {
                executeTokenBucket(key1, 5, 100.0 / 60.0, nowMs, 60);
            }

            // conn-2 should still be available
            var response = executeTokenBucket(key2, 5, 100.0 / 60.0, nowMs, 60);

            assertTrue(isAllowed(response));
        }
    }
}
