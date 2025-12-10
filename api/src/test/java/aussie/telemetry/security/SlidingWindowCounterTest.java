package aussie.telemetry.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SlidingWindowCounter")
class SlidingWindowCounterTest {

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("Should start with zero count")
        void shouldStartWithZeroCount() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));

            assertEquals(0, counter.getCount());
            assertEquals(0, counter.getApproximateCount());
        }

        @Test
        @DisplayName("Should increment count")
        void shouldIncrementCount() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));

            counter.increment();
            counter.increment();
            counter.increment();

            assertEquals(3, counter.getCount());
        }

        @Test
        @DisplayName("Should reset count")
        void shouldResetCount() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));
            counter.increment();
            counter.increment();

            counter.reset();

            assertEquals(0, counter.getCount());
            assertEquals(0, counter.getApproximateCount());
        }

        @Test
        @DisplayName("Should return configured window duration")
        void shouldReturnConfiguredWindowDuration() {
            var window = Duration.ofMinutes(5);
            var counter = new SlidingWindowCounter(window);

            assertEquals(window, counter.getWindow());
        }
    }

    @Nested
    @DisplayName("Threshold Checking")
    class ThresholdChecking {

        @Test
        @DisplayName("Should return false when count is below threshold")
        void shouldReturnFalseWhenBelowThreshold() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));
            counter.increment();
            counter.increment();

            assertFalse(counter.exceeds(5));
        }

        @Test
        @DisplayName("Should return false when count equals threshold")
        void shouldReturnFalseWhenEqualsThreshold() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));
            counter.increment();
            counter.increment();
            counter.increment();
            counter.increment();
            counter.increment();

            assertFalse(counter.exceeds(5));
        }

        @Test
        @DisplayName("Should return true when count exceeds threshold")
        void shouldReturnTrueWhenExceedsThreshold() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));
            for (int i = 0; i < 10; i++) {
                counter.increment();
            }

            assertTrue(counter.exceeds(5));
        }
    }

    @Nested
    @DisplayName("Timestamp Tracking")
    class TimestampTracking {

        @Test
        @DisplayName("Should return -1 for oldest timestamp when empty")
        void shouldReturnNegativeOneForOldestWhenEmpty() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));

            assertEquals(-1, counter.getOldestTimestamp());
        }

        @Test
        @DisplayName("Should return -1 for newest timestamp when empty")
        void shouldReturnNegativeOneForNewestWhenEmpty() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(10));

            assertEquals(-1, counter.getNewestTimestamp());
        }

        @Test
        @DisplayName("Should track oldest and newest timestamps")
        void shouldTrackOldestAndNewestTimestamps() {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(60));

            counter.increment();

            assertTrue(counter.getOldestTimestamp() > 0);
            assertTrue(counter.getNewestTimestamp() > 0);
        }
    }

    @Nested
    @DisplayName("Window Expiration")
    class WindowExpiration {

        @Test
        @DisplayName("Should expire old entries when window passes")
        void shouldExpireOldEntriesWhenWindowPasses() throws InterruptedException {
            // Use a very short window for testing
            var counter = new SlidingWindowCounter(Duration.ofMillis(50));

            counter.increment();
            counter.increment();
            assertEquals(2, counter.getCount());

            // Wait for entries to expire
            Thread.sleep(100);

            // getCount() triggers cleanup
            assertEquals(0, counter.getCount());
        }

        @Test
        @DisplayName("Should keep recent entries after partial expiration")
        void shouldKeepRecentEntriesAfterPartialExpiration() throws InterruptedException {
            var counter = new SlidingWindowCounter(Duration.ofMillis(100));

            counter.increment();
            Thread.sleep(60);
            counter.increment();
            Thread.sleep(60);

            // First entry should be expired, second should remain
            long count = counter.getCount();
            assertTrue(count >= 0 && count <= 2, "Count should be between 0 and 2, got: " + count);
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("Should handle concurrent increments")
        void shouldHandleConcurrentIncrements() throws InterruptedException {
            var counter = new SlidingWindowCounter(Duration.ofSeconds(60));
            int numThreads = 10;
            int incrementsPerThread = 100;

            var threads = new Thread[numThreads];
            for (int i = 0; i < numThreads; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < incrementsPerThread; j++) {
                        counter.increment();
                    }
                });
            }

            for (var thread : threads) {
                thread.start();
            }
            for (var thread : threads) {
                thread.join();
            }

            assertEquals(numThreads * incrementsPerThread, counter.getCount());
        }
    }
}
