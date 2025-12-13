package aussie.adapter.out.ratelimit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.AlgorithmRegistry;
import aussie.core.model.EffectiveRateLimit;
import aussie.core.model.RateLimitAlgorithm;
import aussie.core.model.RateLimitKey;

@DisplayName("InMemoryRateLimiter")
class InMemoryRateLimiterTest {

    private InMemoryRateLimiter rateLimiter;
    private AlgorithmRegistry algorithmRegistry;

    @BeforeEach
    void setUp() {
        algorithmRegistry = new AlgorithmRegistry();
        rateLimiter = new InMemoryRateLimiter(algorithmRegistry, RateLimitAlgorithm.BUCKET, true);
    }

    @Nested
    @DisplayName("Basic operations")
    class BasicOperationTests {

        @Test
        @DisplayName("should allow requests when limit not exceeded")
        void shouldAllowRequestsWhenLimitNotExceeded() {
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 10);

            var decision = rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            assertEquals(9, decision.remaining());
        }

        @Test
        @DisplayName("should reject requests when limit exceeded")
        void shouldRejectRequestsWhenLimitExceeded() {
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 3);

            // Exhaust the bucket
            for (int i = 0; i < 3; i++) {
                var decision = rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
                assertTrue(decision.allowed(), "Request " + (i + 1) + " should be allowed");
            }

            // Next request should be rejected
            var rejected = rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));

            assertFalse(rejected.allowed());
            assertEquals(0, rejected.remaining());
            assertTrue(rejected.retryAfterSeconds() > 0);
        }

        @Test
        @DisplayName("should track separate buckets per client")
        void shouldTrackSeparateBucketsPerClient() {
            var key1 = RateLimitKey.http("client-1", "service-1", "/api");
            var key2 = RateLimitKey.http("client-2", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Client 1 makes 5 requests
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkAndConsume(key1, limit).await().atMost(Duration.ofSeconds(1));
            }

            // Client 2 should still have full capacity
            var decision = rateLimiter.checkAndConsume(key2, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            assertEquals(4, decision.remaining());
        }

        @Test
        @DisplayName("should track separate buckets per service")
        void shouldTrackSeparateBucketsPerService() {
            var key1 = RateLimitKey.http("client-1", "service-1", "/api");
            var key2 = RateLimitKey.http("client-1", "service-2", "/api");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Service 1 gets 5 requests
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkAndConsume(key1, limit).await().atMost(Duration.ofSeconds(1));
            }

            // Service 2 should have full capacity
            var decision = rateLimiter.checkAndConsume(key2, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            assertEquals(4, decision.remaining());
        }
    }

    @Nested
    @DisplayName("Disabled state")
    class DisabledStateTests {

        @Test
        @DisplayName("should always allow when disabled")
        void shouldAlwaysAllowWhenDisabled() {
            var disabledLimiter = new InMemoryRateLimiter(algorithmRegistry, RateLimitAlgorithm.BUCKET, false);
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(1, 60, 1);

            // Even with limit of 1, all requests should be allowed
            for (int i = 0; i < 100; i++) {
                var decision =
                        disabledLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
                assertTrue(decision.allowed(), "Request " + (i + 1) + " should be allowed when disabled");
            }
        }

        @Test
        @DisplayName("isEnabled should return correct state")
        void isEnabledShouldReturnCorrectState() {
            var enabledLimiter = new InMemoryRateLimiter(algorithmRegistry, RateLimitAlgorithm.BUCKET, true);
            var disabledLimiter = new InMemoryRateLimiter(algorithmRegistry, RateLimitAlgorithm.BUCKET, false);

            assertTrue(enabledLimiter.isEnabled());
            assertFalse(disabledLimiter.isEnabled());
        }
    }

    @Nested
    @DisplayName("Status check")
    class StatusCheckTests {

        @Test
        @DisplayName("should return current status without consuming")
        void shouldReturnStatusWithoutConsuming() {
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 10);

            // Make 3 requests
            for (int i = 0; i < 3; i++) {
                rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
            }

            // Check status multiple times - should not consume
            var status1 = rateLimiter.getStatus(key, limit).await().atMost(Duration.ofSeconds(1));
            var status2 = rateLimiter.getStatus(key, limit).await().atMost(Duration.ofSeconds(1));

            assertEquals(status1.remaining(), status2.remaining());
            assertEquals(7, status1.remaining()); // 10 - 3 consumed
        }

        @Test
        @DisplayName("should return allowed status for unknown key")
        void shouldReturnAllowedStatusForUnknownKey() {
            var key = RateLimitKey.http("new-client", "new-service", "/api");
            var limit = new EffectiveRateLimit(100, 60, 10);

            var status = rateLimiter.getStatus(key, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(status.allowed());
        }
    }

    @Nested
    @DisplayName("Reset operations")
    class ResetOperationTests {

        @Test
        @DisplayName("should reset specific key")
        void shouldResetSpecificKey() {
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Exhaust the bucket
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
            }

            // Reset the key
            rateLimiter.reset(key).await().atMost(Duration.ofSeconds(1));

            // Should have full capacity again
            var decision = rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            assertEquals(4, decision.remaining());
        }

        @Test
        @DisplayName("should remove keys matching pattern")
        void shouldRemoveKeysMatchingPattern() {
            var key1 = RateLimitKey.http("client-1", "service-1", "/api");
            var key2 = RateLimitKey.http("client-1", "service-2", "/api");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Make requests for both keys
            rateLimiter.checkAndConsume(key1, limit).await().atMost(Duration.ofSeconds(1));
            rateLimiter.checkAndConsume(key2, limit).await().atMost(Duration.ofSeconds(1));

            assertEquals(2, rateLimiter.getBucketCount());

            // Remove keys matching service-1
            rateLimiter.removeKeysMatching("service-1").await().atMost(Duration.ofSeconds(1));

            assertEquals(1, rateLimiter.getBucketCount());
        }

        @Test
        @DisplayName("clear should remove all state")
        void clearShouldRemoveAllState() {
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Create multiple buckets
            for (int i = 0; i < 5; i++) {
                var key = RateLimitKey.http("client-" + i, "service", "/api");
                rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
            }

            assertEquals(5, rateLimiter.getBucketCount());

            rateLimiter.clear();

            assertEquals(0, rateLimiter.getBucketCount());
        }
    }

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("should handle concurrent requests correctly")
        void shouldHandleConcurrentRequestsCorrectly() throws InterruptedException {
            var key = RateLimitKey.http("client-1", "service-1", "/api");
            var limit = new EffectiveRateLimit(100, 60, 100);

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
                            var decision = rateLimiter
                                    .checkAndConsume(key, limit)
                                    .await()
                                    .atMost(Duration.ofSeconds(1));
                            if (decision.allowed()) {
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

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Total should equal all requests
            assertEquals(threadCount * requestsPerThread, allowedCount.get() + rejectedCount.get());
            // With 100 capacity, we should have exactly 100 allowed
            assertEquals(100, allowedCount.get());
        }

        @Test
        @DisplayName("should isolate concurrent requests to different keys")
        void shouldIsolateConcurrentRequestsToDifferentKeys() throws InterruptedException {
            var limit = new EffectiveRateLimit(100, 60, 10);

            var threadCount = 5;
            var latch = new CountDownLatch(threadCount);
            var results = new ArrayList<Integer>();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final var clientId = "client-" + t;
                executor.submit(() -> {
                    try {
                        var key = RateLimitKey.http(clientId, "service", "/api");
                        var decision =
                                rateLimiter.checkAndConsume(key, limit).await().atMost(Duration.ofSeconds(1));
                        synchronized (results) {
                            results.add((int) decision.remaining());
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Each client should have 9 remaining (10 - 1)
            assertEquals(5, results.size());
            for (var remaining : results) {
                assertEquals(9, remaining, "Each client should have independent bucket");
            }
        }
    }

    @Nested
    @DisplayName("WebSocket key types")
    class WebSocketKeyTypeTests {

        @Test
        @DisplayName("should track WS_CONNECTION keys separately from HTTP")
        void shouldTrackWsConnectionKeysSeparatelyFromHttp() {
            var httpKey = RateLimitKey.http("client", "service", null);
            var wsKey = RateLimitKey.wsConnection("client", "service");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Exhaust HTTP bucket
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkAndConsume(httpKey, limit).await().atMost(Duration.ofSeconds(1));
            }

            // WS should still be available
            var decision = rateLimiter.checkAndConsume(wsKey, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
        }

        @Test
        @DisplayName("should track WS_MESSAGE keys per connection")
        void shouldTrackWsMessageKeysPerConnection() {
            var key1 = RateLimitKey.wsMessage("client", "service", "conn-1");
            var key2 = RateLimitKey.wsMessage("client", "service", "conn-2");
            var limit = new EffectiveRateLimit(100, 60, 5);

            // Exhaust conn-1 bucket
            for (int i = 0; i < 5; i++) {
                rateLimiter.checkAndConsume(key1, limit).await().atMost(Duration.ofSeconds(1));
            }

            // conn-2 should still be available
            var decision = rateLimiter.checkAndConsume(key2, limit).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
        }
    }

    @Nested
    @DisplayName("Bucket count")
    class BucketCountTests {

        @Test
        @DisplayName("should track bucket count correctly")
        void shouldTrackBucketCountCorrectly() {
            var limit = new EffectiveRateLimit(100, 60, 10);

            assertEquals(0, rateLimiter.getBucketCount());

            rateLimiter
                    .checkAndConsume(RateLimitKey.http("c1", "s1", "/api"), limit)
                    .await()
                    .atMost(Duration.ofSeconds(1));
            assertEquals(1, rateLimiter.getBucketCount());

            rateLimiter
                    .checkAndConsume(RateLimitKey.http("c2", "s1", "/api"), limit)
                    .await()
                    .atMost(Duration.ofSeconds(1));
            assertEquals(2, rateLimiter.getBucketCount());

            // Same key should not increase count
            rateLimiter
                    .checkAndConsume(RateLimitKey.http("c1", "s1", "/api"), limit)
                    .await()
                    .atMost(Duration.ofSeconds(1));
            assertEquals(2, rateLimiter.getBucketCount());
        }
    }
}
