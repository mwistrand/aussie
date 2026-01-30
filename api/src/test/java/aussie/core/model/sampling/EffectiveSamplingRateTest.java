package aussie.core.model.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.sampling.EffectiveSamplingRate.SamplingSource;

@DisplayName("EffectiveSamplingRate")
class EffectiveSamplingRateTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with valid rate and source")
        void shouldCreateWithValidRateAndSource() {
            var rate = new EffectiveSamplingRate(0.5, SamplingSource.SERVICE);

            assertEquals(0.5, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("should reject rate below 0.0")
        void shouldRejectRateBelowZero() {
            assertThrows(
                    IllegalArgumentException.class, () -> new EffectiveSamplingRate(-0.1, SamplingSource.PLATFORM));
        }

        @Test
        @DisplayName("should reject rate above 1.0")
        void shouldRejectRateAboveOne() {
            assertThrows(IllegalArgumentException.class, () -> new EffectiveSamplingRate(1.1, SamplingSource.PLATFORM));
        }

        @Test
        @DisplayName("should reject null source")
        void shouldRejectNullSource() {
            assertThrows(IllegalArgumentException.class, () -> new EffectiveSamplingRate(0.5, null));
        }

        @Test
        @DisplayName("should accept boundary values")
        void shouldAcceptBoundaryValues() {
            var zero = new EffectiveSamplingRate(0.0, SamplingSource.PLATFORM);
            var one = new EffectiveSamplingRate(1.0, SamplingSource.PLATFORM);

            assertEquals(0.0, zero.rate());
            assertEquals(1.0, one.rate());
        }
    }

    @Nested
    @DisplayName("clampToPlatformBounds")
    class ClampToPlatformBounds {

        @Test
        @DisplayName("should not change rate within bounds")
        void shouldNotChangeRateWithinBounds() {
            var rate = new EffectiveSamplingRate(0.5, SamplingSource.SERVICE);

            var clamped = rate.clampToPlatformBounds(0.0, 1.0);

            assertEquals(0.5, clamped.rate());
            assertEquals(SamplingSource.SERVICE, clamped.source());
        }

        @Test
        @DisplayName("should clamp to minimum")
        void shouldClampToMinimum() {
            var rate = new EffectiveSamplingRate(0.1, SamplingSource.ENDPOINT);

            var clamped = rate.clampToPlatformBounds(0.2, 1.0);

            assertEquals(0.2, clamped.rate());
            assertEquals(SamplingSource.ENDPOINT, clamped.source());
        }

        @Test
        @DisplayName("should clamp to maximum")
        void shouldClampToMaximum() {
            var rate = new EffectiveSamplingRate(0.9, SamplingSource.SERVICE);

            var clamped = rate.clampToPlatformBounds(0.0, 0.8);

            assertEquals(0.8, clamped.rate());
            assertEquals(SamplingSource.SERVICE, clamped.source());
        }

        @Test
        @DisplayName("should preserve source after clamping")
        void shouldPreserveSourceAfterClamping() {
            var rate = new EffectiveSamplingRate(0.5, SamplingSource.ENDPOINT);

            var clamped = rate.clampToPlatformBounds(0.6, 1.0);

            assertEquals(SamplingSource.ENDPOINT, clamped.source());
        }
    }

    @Nested
    @DisplayName("Convenience methods")
    class ConvenienceMethods {

        @Test
        @DisplayName("isNoSampling should return true for rate 1.0")
        void isNoSamplingShouldReturnTrueForRateOne() {
            var rate = new EffectiveSamplingRate(1.0, SamplingSource.PLATFORM);

            assertTrue(rate.isNoSampling());
        }

        @Test
        @DisplayName("isNoSampling should return false for rate less than 1.0")
        void isNoSamplingShouldReturnFalseForRateLessThanOne() {
            var rate = new EffectiveSamplingRate(0.99, SamplingSource.PLATFORM);

            assertFalse(rate.isNoSampling());
        }

        @Test
        @DisplayName("isDropAll should return true for rate 0.0")
        void isDropAllShouldReturnTrueForRateZero() {
            var rate = new EffectiveSamplingRate(0.0, SamplingSource.PLATFORM);

            assertTrue(rate.isDropAll());
        }

        @Test
        @DisplayName("isDropAll should return false for rate greater than 0.0")
        void isDropAllShouldReturnFalseForRateGreaterThanZero() {
            var rate = new EffectiveSamplingRate(0.01, SamplingSource.PLATFORM);

            assertFalse(rate.isDropAll());
        }
    }
}
