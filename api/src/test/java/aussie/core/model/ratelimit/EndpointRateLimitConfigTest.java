package aussie.core.model.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EndpointRateLimitConfig")
class EndpointRateLimitConfigTest {

    @Nested
    @DisplayName("Compact constructor null coalescing")
    class NullCoalescing {

        @Test
        @DisplayName("should default null requestsPerWindow to empty Optional")
        void shouldDefaultNullRequestsPerWindow() {
            var config = new EndpointRateLimitConfig(null, Optional.of(60L), Optional.of(100L));

            assertTrue(config.requestsPerWindow().isEmpty());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertEquals(100L, config.burstCapacity().orElseThrow());
        }

        @Test
        @DisplayName("should default null windowSeconds to empty Optional")
        void shouldDefaultNullWindowSeconds() {
            var config = new EndpointRateLimitConfig(Optional.of(100L), null, Optional.of(50L));

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertTrue(config.windowSeconds().isEmpty());
            assertEquals(50L, config.burstCapacity().orElseThrow());
        }

        @Test
        @DisplayName("should default null burstCapacity to empty Optional")
        void shouldDefaultNullBurstCapacity() {
            var config = new EndpointRateLimitConfig(Optional.of(100L), Optional.of(60L), null);

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertTrue(config.burstCapacity().isEmpty());
        }

        @Test
        @DisplayName("should default all null values to empty Optionals")
        void shouldDefaultAllNullValues() {
            var config = new EndpointRateLimitConfig(null, null, null);

            assertTrue(config.requestsPerWindow().isEmpty());
            assertTrue(config.windowSeconds().isEmpty());
            assertTrue(config.burstCapacity().isEmpty());
        }

        @Test
        @DisplayName("should preserve non-null values")
        void shouldPreserveNonNullValues() {
            var config = new EndpointRateLimitConfig(Optional.of(100L), Optional.of(60L), Optional.of(150L));

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertEquals(150L, config.burstCapacity().orElseThrow());
        }
    }

    @Nested
    @DisplayName("hasConfiguration")
    class HasConfiguration {

        @Test
        @DisplayName("should return false when all values are empty")
        void shouldReturnFalseWhenAllEmpty() {
            var config = EndpointRateLimitConfig.defaults();

            assertFalse(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only requestsPerWindow is present")
        void shouldReturnTrueWhenOnlyRequestsPerWindow() {
            var config = new EndpointRateLimitConfig(Optional.of(100L), Optional.empty(), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only windowSeconds is present")
        void shouldReturnTrueWhenOnlyWindowSeconds() {
            var config = new EndpointRateLimitConfig(Optional.empty(), Optional.of(60L), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only burstCapacity is present")
        void shouldReturnTrueWhenOnlyBurstCapacity() {
            var config = new EndpointRateLimitConfig(Optional.empty(), Optional.empty(), Optional.of(150L));

            assertTrue(config.hasConfiguration());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("of(3 args) should set all values")
        void ofThreeArgsShouldSetAllValues() {
            var config = EndpointRateLimitConfig.of(100, 60, 150);

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertEquals(150L, config.burstCapacity().orElseThrow());
        }

        @Test
        @DisplayName("of(2 args) should set requests and window, leave burst empty")
        void ofTwoArgsShouldSetRequestsAndWindow() {
            var config = EndpointRateLimitConfig.of(100, 60);

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertTrue(config.burstCapacity().isEmpty());
        }

        @Test
        @DisplayName("defaults should create config with all empty values")
        void defaultsShouldCreateAllEmpty() {
            var config = EndpointRateLimitConfig.defaults();

            assertTrue(config.requestsPerWindow().isEmpty());
            assertTrue(config.windowSeconds().isEmpty());
            assertTrue(config.burstCapacity().isEmpty());
        }
    }
}
