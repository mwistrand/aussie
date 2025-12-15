package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.*;

@DisplayName("BucketAlgorithm")
class BucketAlgorithmTest {

    private BucketAlgorithm algorithm;

    @BeforeEach
    void setUp() {
        algorithm = BucketAlgorithm.getInstance();
    }

    @Nested
    @DisplayName("Algorithm identification")
    class AlgorithmIdentificationTests {

        @Test
        @DisplayName("should return BUCKET algorithm type")
        void shouldReturnBucketAlgorithmType() {
            assertEquals(RateLimitAlgorithm.BUCKET, algorithm.algorithm());
        }

        @Test
        @DisplayName("should return singleton instance")
        void shouldReturnSingletonInstance() {
            var instance1 = BucketAlgorithm.getInstance();
            var instance2 = BucketAlgorithm.getInstance();
            assertTrue(instance1 == instance2, "Should return same instance");
        }
    }

    @Nested
    @DisplayName("Initial state creation")
    class InitialStateTests {

        @Test
        @DisplayName("should create initial state with full capacity")
        void shouldCreateInitialStateWithFullCapacity() {
            var limit = new EffectiveRateLimit(100, 60, 50);
            var nowMillis = System.currentTimeMillis();

            var state = algorithm.createInitialState(limit, nowMillis);

            assertNotNull(state);
            assertTrue(state instanceof BucketState);
            var bucketState = (BucketState) state;
            assertEquals(50, bucketState.tokens(), "Should have burst capacity tokens");
            assertEquals(nowMillis, bucketState.lastRefillMillis());
        }
    }

    @Nested
    @DisplayName("Check and consume")
    class CheckAndConsumeTests {

        @Test
        @DisplayName("should allow request when tokens available")
        void shouldAllowRequestWhenTokensAvailable() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(5, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            assertEquals(4, decision.remaining(), "Should have consumed one token");
            assertNotNull(decision.newState());
        }

        @Test
        @DisplayName("should reject request when no tokens available")
        void shouldRejectRequestWhenNoTokensAvailable() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(0, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertFalse(decision.allowed());
            assertEquals(0, decision.remaining());
            assertTrue(decision.retryAfterSeconds() > 0, "Should have positive retry-after");
        }

        @Test
        @DisplayName("should create initial state when current state is null")
        void shouldCreateInitialStateWhenNull() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();

            var decision = algorithm.checkAndConsume(null, limit, nowMillis);

            assertTrue(decision.allowed());
            assertEquals(9, decision.remaining(), "Should have burst capacity - 1 tokens");
        }

        @Test
        @DisplayName("should consume exactly one token per request")
        void shouldConsumeExactlyOneToken() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(10, nowMillis);

            var decision1 = algorithm.checkAndConsume(state, limit, nowMillis);
            var decision2 = algorithm.checkAndConsume(decision1.newState(), limit, nowMillis);
            var decision3 = algorithm.checkAndConsume(decision2.newState(), limit, nowMillis);

            assertEquals(9, decision1.remaining());
            assertEquals(8, decision2.remaining());
            assertEquals(7, decision3.remaining());
        }
    }

    @Nested
    @DisplayName("Token refill")
    class TokenRefillTests {

        @Test
        @DisplayName("should refill tokens based on elapsed time")
        void shouldRefillTokensBasedOnElapsedTime() {
            // 100 requests per 60 seconds = ~1.67 tokens per second
            var limit = new EffectiveRateLimit(100, 60, 100);
            var startMillis = 1000000L;
            var state = new BucketState(0, startMillis);

            // After 60 seconds, should have refilled ~100 tokens
            var nowMillis = startMillis + 60000;
            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            // Should have refilled to capacity (100) then consumed 1 = 99
            assertEquals(99, decision.remaining());
        }

        @Test
        @DisplayName("should not exceed burst capacity when refilling")
        void shouldNotExceedBurstCapacityWhenRefilling() {
            var limit = new EffectiveRateLimit(100, 60, 50);
            var startMillis = 1000000L;
            var state = new BucketState(40, startMillis);

            // After a long time, tokens should be capped at burst capacity
            var nowMillis = startMillis + 120000; // 2 minutes later
            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            // Should be capped at 50 (burst capacity) - 1 consumed = 49
            assertEquals(49, decision.remaining());
        }

        @Test
        @DisplayName("should not refill when no time has elapsed")
        void shouldNotRefillWhenNoTimeElapsed() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = 1000000L;
            var state = new BucketState(5, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            assertEquals(4, decision.remaining()); // 5 - 1 consumed
        }
    }

    @Nested
    @DisplayName("Rate limit response values")
    class ResponseValuesTests {

        @Test
        @DisplayName("should set correct limit value in decision")
        void shouldSetCorrectLimitValue() {
            var limit = new EffectiveRateLimit(200, 60, 50);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(10, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertEquals(200, decision.limit());
        }

        @Test
        @DisplayName("should set correct window seconds in decision")
        void shouldSetCorrectWindowSeconds() {
            var limit = new EffectiveRateLimit(100, 120, 50);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(10, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertEquals(120, decision.windowSeconds());
        }

        @Test
        @DisplayName("should set reset time to end of current window")
        void shouldSetResetTimeToEndOfWindow() {
            var limit = new EffectiveRateLimit(100, 60, 50);
            var nowMillis = 1700000000000L; // Some fixed time
            var state = new BucketState(10, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertNotNull(decision.resetAt());
            // Reset time should be in the future
            assertTrue(decision.resetAtEpochSeconds() > nowMillis / 1000);
        }

        @Test
        @DisplayName("should calculate retry-after based on refill rate")
        void shouldCalculateRetryAfterBasedOnRefillRate() {
            // 1 request per second = 1 token per second refill rate
            var limit = new EffectiveRateLimit(60, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(0, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertFalse(decision.allowed());
            assertEquals(1, decision.retryAfterSeconds(), "Should retry after 1 second for 1 token/sec refill");
        }

        @Test
        @DisplayName("should track request count correctly")
        void shouldTrackRequestCountCorrectly() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(7, nowMillis);

            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            // Request count = burst capacity - remaining = 10 - 6 = 4
            // (we started with 7, consumed 1, so 6 remaining means 4 used)
            assertEquals(4, decision.requestCount());
        }
    }

    @Nested
    @DisplayName("Status check (non-consuming)")
    class StatusCheckTests {

        @Test
        @DisplayName("should return current status without consuming")
        void shouldReturnStatusWithoutConsuming() {
            var limit = new EffectiveRateLimit(100, 60, 10);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(5, nowMillis);

            var status = algorithm.computeStatus(state, limit, nowMillis);

            assertTrue(status.allowed());
            assertEquals(5, status.remaining(), "Should not consume token for status check");
        }

        @Test
        @DisplayName("should apply refill when checking status")
        void shouldApplyRefillWhenCheckingStatus() {
            var limit = new EffectiveRateLimit(60, 60, 10);
            var startMillis = 1000000L;
            var state = new BucketState(5, startMillis);

            // After 5 seconds, should have refilled 5 tokens (1/sec)
            var nowMillis = startMillis + 5000;
            var status = algorithm.computeStatus(state, limit, nowMillis);

            assertTrue(status.allowed());
            assertEquals(10, status.remaining(), "Should have refilled to capacity");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle very high refill rates")
        void shouldHandleVeryHighRefillRates() {
            // 10000 requests per second
            var limit = new EffectiveRateLimit(10000, 1, 10000);
            var startMillis = 1000000L;
            var state = new BucketState(0, startMillis);

            // After 1 second, should have refilled to capacity
            var nowMillis = startMillis + 1000;
            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            assertEquals(9999, decision.remaining());
        }

        @Test
        @DisplayName("should handle very low refill rates")
        void shouldHandleVeryLowRefillRates() {
            // 1 request per hour
            var limit = new EffectiveRateLimit(1, 3600, 1);
            var startMillis = 1000000L;
            var state = new BucketState(0, startMillis);

            // After 1 hour, should have refilled 1 token
            var nowMillis = startMillis + 3600000;
            var decision = algorithm.checkAndConsume(state, limit, nowMillis);

            assertTrue(decision.allowed());
            assertEquals(0, decision.remaining());
        }

        @Test
        @DisplayName("should exhaust all tokens in burst")
        void shouldExhaustAllTokensInBurst() {
            var limit = new EffectiveRateLimit(100, 60, 5);
            var nowMillis = System.currentTimeMillis();
            var state = new BucketState(5, nowMillis);

            // Consume all 5 tokens
            var current = state;
            for (int i = 0; i < 5; i++) {
                var decision = algorithm.checkAndConsume(current, limit, nowMillis);
                assertTrue(decision.allowed(), "Request " + (i + 1) + " should be allowed");
                current = (BucketState) decision.newState();
            }

            // 6th request should be rejected
            var rejected = algorithm.checkAndConsume(current, limit, nowMillis);
            assertFalse(rejected.allowed(), "Request after exhaustion should be rejected");
        }
    }
}
