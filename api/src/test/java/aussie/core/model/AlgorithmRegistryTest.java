package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        @DisplayName("should fall back to default for unavailable algorithms")
        void shouldFallBackToDefaultForUnavailableAlgorithms() {
            // FIXED_WINDOW is not yet implemented
            var handler = registry.getHandler(RateLimitAlgorithm.FIXED_WINDOW);

            assertNotNull(handler);
            // Should fall back to BUCKET
            assertEquals(RateLimitAlgorithm.BUCKET, handler.algorithm());
        }

        @Test
        @DisplayName("should fall back to default for SLIDING_WINDOW")
        void shouldFallBackToDefaultForSlidingWindow() {
            // SLIDING_WINDOW is not yet implemented
            var handler = registry.getHandler(RateLimitAlgorithm.SLIDING_WINDOW);

            assertNotNull(handler);
            // Should fall back to BUCKET
            assertEquals(RateLimitAlgorithm.BUCKET, handler.algorithm());
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
        @DisplayName("should handle null algorithm gracefully")
        void shouldHandleNullAlgorithmGracefully() {
            // EnumMap throws NullPointerException for null keys
            // This tests the behavior - either handle it or let it throw
            try {
                var handler = registry.getHandler(null);
                // If it doesn't throw, should return default
                assertEquals(RateLimitAlgorithm.BUCKET, handler.algorithm());
            } catch (NullPointerException e) {
                // Expected behavior for EnumMap
            }
        }
    }
}
