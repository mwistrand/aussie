package aussie.core.service.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.BucketAlgorithm;
import aussie.core.model.ratelimit.RateLimitAlgorithm;

@DisplayName("AlgorithmRegistry")
class AlgorithmRegistryTest {

    private AlgorithmRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AlgorithmRegistry();
    }

    @Nested
    @DisplayName("Handler lookup")
    class HandlerLookupTests {

        @Test
        @DisplayName("should return bucket algorithm handler for BUCKET")
        void shouldReturnBucketAlgorithmHandlerForBucket() {
            var handler = registry.getHandler(RateLimitAlgorithm.BUCKET);

            assertNotNull(handler);
            assertEquals(RateLimitAlgorithm.BUCKET, handler.algorithm());
            assertTrue(handler instanceof BucketAlgorithm);
        }

        @Test
        @DisplayName("should reject unavailable algorithms")
        void shouldRejectUnavailableAlgorithms() {
            assertThrows(IllegalArgumentException.class, () -> registry.getHandler(RateLimitAlgorithm.FIXED_WINDOW));
        }

        @Test
        @DisplayName("should reject unavailable sliding window")
        void shouldRejectUnavailableSlidingWindow() {
            assertThrows(IllegalArgumentException.class, () -> registry.getHandler(RateLimitAlgorithm.SLIDING_WINDOW));
        }
    }

    @Nested
    @DisplayName("Availability check")
    class AvailabilityTests {

        @Test
        @DisplayName("should report BUCKET as available")
        void shouldReportBucketAsAvailable() {
            assertTrue(registry.isAvailable(RateLimitAlgorithm.BUCKET));
        }

        @Test
        @DisplayName("should report FIXED_WINDOW as not available")
        void shouldReportFixedWindowAsNotAvailable() {
            assertFalse(registry.isAvailable(RateLimitAlgorithm.FIXED_WINDOW));
        }

        @Test
        @DisplayName("should report SLIDING_WINDOW as not available")
        void shouldReportSlidingWindowAsNotAvailable() {
            assertFalse(registry.isAvailable(RateLimitAlgorithm.SLIDING_WINDOW));
        }
    }

    @Nested
    @DisplayName("Default handler")
    class DefaultHandlerTests {

        @Test
        @DisplayName("should return bucket as default handler")
        void shouldReturnBucketAsDefaultHandler() {
            var defaultHandler = registry.getDefaultHandler();

            assertNotNull(defaultHandler);
            assertEquals(RateLimitAlgorithm.BUCKET, defaultHandler.algorithm());
        }

        @Test
        @DisplayName("default handler should be same as bucket handler")
        void defaultHandlerShouldBeSameAsBucketHandler() {
            var defaultHandler = registry.getDefaultHandler();
            var bucketHandler = registry.getHandler(RateLimitAlgorithm.BUCKET);

            assertTrue(defaultHandler == bucketHandler, "Should be same instance");
        }
    }

    @Nested
    @DisplayName("Handler consistency")
    class ConsistencyTests {

        @Test
        @DisplayName("should return same handler instance for same algorithm")
        void shouldReturnSameHandlerInstanceForSameAlgorithm() {
            var handler1 = registry.getHandler(RateLimitAlgorithm.BUCKET);
            var handler2 = registry.getHandler(RateLimitAlgorithm.BUCKET);

            assertTrue(handler1 == handler2, "Should return same handler instance");
        }

        @Test
        @DisplayName("should reject null algorithm")
        void shouldRejectNullAlgorithm() {
            assertThrows(IllegalArgumentException.class, () -> registry.getHandler(null));
        }
    }
}
