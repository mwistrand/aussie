package aussie.core.model.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("BucketState")
class BucketStateTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("should accept zero tokens")
        void shouldAcceptZeroTokens() {
            var state = new BucketState(0, 1000L);

            assertEquals(0, state.tokens());
        }

        @Test
        @DisplayName("should accept zero lastRefillMillis")
        void shouldAcceptZeroLastRefillMillis() {
            var state = new BucketState(10, 0);

            assertEquals(0, state.lastRefillMillis());
        }

        @Test
        @DisplayName("should reject negative tokens")
        void shouldRejectNegativeTokens() {
            assertThrows(IllegalArgumentException.class, () -> new BucketState(-1, 1000L));
        }

        @Test
        @DisplayName("should reject negative lastRefillMillis")
        void shouldRejectNegativeLastRefillMillis() {
            assertThrows(IllegalArgumentException.class, () -> new BucketState(10, -1));
        }
    }

    @Nested
    @DisplayName("consume")
    class Consume {

        @Test
        @DisplayName("should return state with one fewer token")
        void shouldReturnStateWithOneFewerToken() {
            var state = new BucketState(5, 1000L);

            var consumed = state.consume();

            assertEquals(4, consumed.tokens());
            assertEquals(1000L, consumed.lastRefillMillis());
        }

        @Test
        @DisplayName("should consume down to zero")
        void shouldConsumeDownToZero() {
            var state = new BucketState(1, 1000L);

            var consumed = state.consume();

            assertEquals(0, consumed.tokens());
        }

        @Test
        @DisplayName("should throw when no tokens available")
        void shouldThrowWhenNoTokens() {
            var state = new BucketState(0, 1000L);

            assertThrows(IllegalStateException.class, state::consume);
        }
    }

    @Nested
    @DisplayName("refill")
    class Refill {

        @Test
        @DisplayName("should add tokens up to capacity")
        void shouldAddTokensUpToCapacity() {
            var state = new BucketState(5, 1000L);

            var refilled = state.refill(10, 12, 2000L);

            assertEquals(12, refilled.tokens());
            assertEquals(2000L, refilled.lastRefillMillis());
        }

        @Test
        @DisplayName("should cap at capacity")
        void shouldCapAtCapacity() {
            var state = new BucketState(5, 1000L);

            var refilled = state.refill(100, 10, 2000L);

            assertEquals(10, refilled.tokens());
        }
    }

    @Nested
    @DisplayName("calculateRefillTokens")
    class CalculateRefillTokens {

        @Test
        @DisplayName("should calculate tokens based on elapsed time")
        void shouldCalculateTokensBasedOnElapsedTime() {
            var state = new BucketState(0, 1000L);

            // 2 tokens per second, 5 seconds elapsed
            var tokens = state.calculateRefillTokens(2.0, 6000L);

            assertEquals(10, tokens);
        }

        @Test
        @DisplayName("should return zero when no time has elapsed")
        void shouldReturnZeroWhenNoTimeElapsed() {
            var state = new BucketState(0, 1000L);

            var tokens = state.calculateRefillTokens(2.0, 1000L);

            assertEquals(0, tokens);
        }
    }

    @Nested
    @DisplayName("RateLimitState interface")
    class RateLimitStateInterface {

        @Test
        @DisplayName("remaining should return tokens")
        void remainingShouldReturnTokens() {
            var state = new BucketState(42, 1000L);

            assertEquals(42, state.remaining());
        }

        @Test
        @DisplayName("timestampMillis should return lastRefillMillis")
        void timestampMillisShouldReturnLastRefillMillis() {
            var state = new BucketState(10, 5000L);

            assertEquals(5000L, state.timestampMillis());
        }
    }

    @Nested
    @DisplayName("initial factory method")
    class InitialFactory {

        @Test
        @DisplayName("should create state with specified capacity")
        void shouldCreateStateWithCapacity() {
            var state = BucketState.initial(100);

            assertEquals(100, state.tokens());
        }
    }
}
