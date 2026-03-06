package aussie.core.model.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceRateLimitConfig")
class ServiceRateLimitConfigTest {

    @Nested
    @DisplayName("Compact constructor null coalescing")
    class NullCoalescing {

        @Test
        @DisplayName("should default null requestsPerWindow to empty Optional")
        void shouldDefaultNullRequestsPerWindow() {
            var config = new ServiceRateLimitConfig(null, Optional.of(60L), Optional.of(100L), Optional.empty());

            assertTrue(config.requestsPerWindow().isEmpty());
            assertEquals(60L, config.windowSeconds().orElseThrow());
        }

        @Test
        @DisplayName("should default null windowSeconds to empty Optional")
        void shouldDefaultNullWindowSeconds() {
            var config = new ServiceRateLimitConfig(Optional.of(100L), null, Optional.of(50L), Optional.empty());

            assertTrue(config.windowSeconds().isEmpty());
            assertEquals(100L, config.requestsPerWindow().orElseThrow());
        }

        @Test
        @DisplayName("should default null burstCapacity to empty Optional")
        void shouldDefaultNullBurstCapacity() {
            var config = new ServiceRateLimitConfig(Optional.of(100L), Optional.of(60L), null, Optional.empty());

            assertTrue(config.burstCapacity().isEmpty());
        }

        @Test
        @DisplayName("should default null websocket to empty Optional")
        void shouldDefaultNullWebsocket() {
            var config = new ServiceRateLimitConfig(Optional.of(100L), Optional.of(60L), Optional.of(50L), null);

            assertTrue(config.websocket().isEmpty());
        }

        @Test
        @DisplayName("should default all null values to empty Optionals")
        void shouldDefaultAllNullValues() {
            var config = new ServiceRateLimitConfig(null, null, null, null);

            assertTrue(config.requestsPerWindow().isEmpty());
            assertTrue(config.windowSeconds().isEmpty());
            assertTrue(config.burstCapacity().isEmpty());
            assertTrue(config.websocket().isEmpty());
        }

        @Test
        @DisplayName("should preserve non-null values")
        void shouldPreserveNonNullValues() {
            final var wsConfig = ServiceWebSocketRateLimitConfig.defaults();
            var config = new ServiceRateLimitConfig(
                    Optional.of(100L), Optional.of(60L), Optional.of(150L), Optional.of(wsConfig));

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertEquals(150L, config.burstCapacity().orElseThrow());
            assertTrue(config.websocket().isPresent());
        }
    }

    @Nested
    @DisplayName("Three-arg constructor")
    class ThreeArgConstructor {

        @Test
        @DisplayName("should set websocket to empty Optional")
        void shouldSetWebsocketToEmpty() {
            var config = new ServiceRateLimitConfig(Optional.of(100L), Optional.of(60L), Optional.of(50L));

            assertTrue(config.websocket().isEmpty());
        }
    }

    @Nested
    @DisplayName("hasConfiguration")
    class HasConfiguration {

        @Test
        @DisplayName("should return false when all values are empty")
        void shouldReturnFalseWhenAllEmpty() {
            var config = ServiceRateLimitConfig.defaults();

            assertFalse(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only requestsPerWindow is present")
        void shouldReturnTrueWhenOnlyRequestsPerWindow() {
            var config =
                    new ServiceRateLimitConfig(Optional.of(100L), Optional.empty(), Optional.empty(), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only windowSeconds is present")
        void shouldReturnTrueWhenOnlyWindowSeconds() {
            var config =
                    new ServiceRateLimitConfig(Optional.empty(), Optional.of(60L), Optional.empty(), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only burstCapacity is present")
        void shouldReturnTrueWhenOnlyBurstCapacity() {
            var config =
                    new ServiceRateLimitConfig(Optional.empty(), Optional.empty(), Optional.of(50L), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only websocket has configuration")
        void shouldReturnTrueWhenOnlyWebsocketHasConfig() {
            final var wsValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(10, 60);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(wsValues, null);
            var config = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return false when websocket is present but has no configuration")
        void shouldReturnFalseWhenWebsocketPresentButEmpty() {
            final var wsConfig = ServiceWebSocketRateLimitConfig.defaults();
            var config = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));

            assertFalse(config.hasConfiguration());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("of(3 args) should set all HTTP values")
        void ofThreeArgsShouldSetAllHttpValues() {
            var config = ServiceRateLimitConfig.of(100, 60, 150);

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertEquals(150L, config.burstCapacity().orElseThrow());
            assertTrue(config.websocket().isEmpty());
        }

        @Test
        @DisplayName("of(2 args) should set requests and window")
        void ofTwoArgsShouldSetRequestsAndWindow() {
            var config = ServiceRateLimitConfig.of(100, 60);

            assertEquals(100L, config.requestsPerWindow().orElseThrow());
            assertEquals(60L, config.windowSeconds().orElseThrow());
            assertTrue(config.burstCapacity().isEmpty());
            assertTrue(config.websocket().isEmpty());
        }

        @Test
        @DisplayName("defaults should create config with all empty values")
        void defaultsShouldCreateAllEmpty() {
            var config = ServiceRateLimitConfig.defaults();

            assertTrue(config.requestsPerWindow().isEmpty());
            assertTrue(config.windowSeconds().isEmpty());
            assertTrue(config.burstCapacity().isEmpty());
            assertTrue(config.websocket().isEmpty());
        }
    }
}
