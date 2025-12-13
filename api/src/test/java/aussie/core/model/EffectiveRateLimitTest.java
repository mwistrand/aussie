package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EffectiveRateLimit")
class EffectiveRateLimitTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("should accept valid values")
        void shouldAcceptValidValues() {
            var limit = new EffectiveRateLimit(100, 60, 150);

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(150, limit.burstCapacity());
        }

        @Test
        @DisplayName("should accept zero requests per window")
        void shouldAcceptZeroRequestsPerWindow() {
            var limit = new EffectiveRateLimit(0, 60, 0);

            assertEquals(0, limit.requestsPerWindow());
        }

        @Test
        @DisplayName("should reject negative requests per window")
        void shouldRejectNegativeRequestsPerWindow() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EffectiveRateLimit(-1, 60, 100),
                    "requestsPerWindow must be non-negative");
        }

        @Test
        @DisplayName("should reject zero window seconds")
        void shouldRejectZeroWindowSeconds() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EffectiveRateLimit(100, 0, 100),
                    "windowSeconds must be positive");
        }

        @Test
        @DisplayName("should reject negative window seconds")
        void shouldRejectNegativeWindowSeconds() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EffectiveRateLimit(100, -1, 100),
                    "windowSeconds must be positive");
        }

        @Test
        @DisplayName("should reject negative burst capacity")
        void shouldRejectNegativeBurstCapacity() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EffectiveRateLimit(100, 60, -1),
                    "burstCapacity must be non-negative");
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("unlimited should create max value limits")
        void unlimitedShouldCreateMaxValueLimits() {
            var limit = EffectiveRateLimit.unlimited();

            assertEquals(Long.MAX_VALUE, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(Long.MAX_VALUE, limit.burstCapacity());
        }

        @Test
        @DisplayName("of should create limit with burst equal to requests")
        void ofShouldCreateLimitWithBurstEqualToRequests() {
            var limit = EffectiveRateLimit.of(100, 60);

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(100, limit.burstCapacity());
        }
    }

    @Nested
    @DisplayName("capAtPlatformMax")
    class CapAtPlatformMax {

        @Test
        @DisplayName("should return same instance when under platform max")
        void shouldReturnSameInstanceWhenUnderMax() {
            var limit = new EffectiveRateLimit(100, 60, 150);

            var capped = limit.capAtPlatformMax(200);

            assertSame(limit, capped);
        }

        @Test
        @DisplayName("should cap requests when above platform max")
        void shouldCapRequestsWhenAboveMax() {
            var limit = new EffectiveRateLimit(200, 60, 150);

            var capped = limit.capAtPlatformMax(100);

            assertEquals(100, capped.requestsPerWindow());
            assertEquals(60, capped.windowSeconds());
            assertEquals(100, capped.burstCapacity());
        }

        @Test
        @DisplayName("should cap burst when above platform max")
        void shouldCapBurstWhenAboveMax() {
            var limit = new EffectiveRateLimit(100, 60, 200);

            var capped = limit.capAtPlatformMax(150);

            assertEquals(100, capped.requestsPerWindow());
            assertEquals(60, capped.windowSeconds());
            assertEquals(150, capped.burstCapacity());
        }

        @Test
        @DisplayName("should cap both when both above platform max")
        void shouldCapBothWhenBothAboveMax() {
            var limit = new EffectiveRateLimit(200, 60, 300);

            var capped = limit.capAtPlatformMax(100);

            assertEquals(100, capped.requestsPerWindow());
            assertEquals(60, capped.windowSeconds());
            assertEquals(100, capped.burstCapacity());
        }
    }

    @Nested
    @DisplayName("refillRatePerSecond")
    class RefillRatePerSecond {

        @Test
        @DisplayName("should calculate correct refill rate")
        void shouldCalculateCorrectRefillRate() {
            var limit = new EffectiveRateLimit(60, 60, 100);

            assertEquals(1.0, limit.refillRatePerSecond(), 0.001);
        }

        @Test
        @DisplayName("should handle fractional refill rate")
        void shouldHandleFractionalRefillRate() {
            var limit = new EffectiveRateLimit(100, 60, 100);

            assertEquals(100.0 / 60.0, limit.refillRatePerSecond(), 0.001);
        }

        @Test
        @DisplayName("should handle high volume refill rate")
        void shouldHandleHighVolumeRefillRate() {
            var limit = new EffectiveRateLimit(10000, 60, 10000);

            assertEquals(10000.0 / 60.0, limit.refillRatePerSecond(), 0.001);
        }
    }
}
