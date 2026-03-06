package aussie.core.model.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceWebSocketRateLimitConfig")
class ServiceWebSocketRateLimitConfigTest {

    @Nested
    @DisplayName("Compact constructor null coalescing")
    class NullCoalescing {

        @Test
        @DisplayName("should default null connection to empty Optional")
        void shouldDefaultNullConnection() {
            final var msgValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(10, 60);
            var config = new ServiceWebSocketRateLimitConfig(null, Optional.of(msgValues));

            assertTrue(config.connection().isEmpty());
            assertTrue(config.message().isPresent());
        }

        @Test
        @DisplayName("should default null message to empty Optional")
        void shouldDefaultNullMessage() {
            final var connValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(5, 60);
            var config = new ServiceWebSocketRateLimitConfig(Optional.of(connValues), null);

            assertTrue(config.connection().isPresent());
            assertTrue(config.message().isEmpty());
        }

        @Test
        @DisplayName("should default all null values to empty Optionals")
        void shouldDefaultAllNullValues() {
            var config = new ServiceWebSocketRateLimitConfig(null, null);

            assertTrue(config.connection().isEmpty());
            assertTrue(config.message().isEmpty());
        }

        @Test
        @DisplayName("should preserve non-null values")
        void shouldPreserveNonNullValues() {
            final var connValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(5, 60);
            final var msgValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(100, 60);
            var config = new ServiceWebSocketRateLimitConfig(Optional.of(connValues), Optional.of(msgValues));

            assertTrue(config.connection().isPresent());
            assertTrue(config.message().isPresent());
        }
    }

    @Nested
    @DisplayName("hasConfiguration")
    class HasConfiguration {

        @Test
        @DisplayName("should return false when both are empty")
        void shouldReturnFalseWhenBothEmpty() {
            var config = ServiceWebSocketRateLimitConfig.defaults();

            assertFalse(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only connection is present")
        void shouldReturnTrueWhenOnlyConnection() {
            final var connValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(5, 60);
            var config = new ServiceWebSocketRateLimitConfig(Optional.of(connValues), Optional.empty());

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return true when only message is present")
        void shouldReturnTrueWhenOnlyMessage() {
            final var msgValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(100, 60);
            var config = new ServiceWebSocketRateLimitConfig(Optional.empty(), Optional.of(msgValues));

            assertTrue(config.hasConfiguration());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("of should wrap non-null values in Optionals")
        void ofShouldWrapNonNullValues() {
            final var connValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(5, 60);
            final var msgValues = ServiceWebSocketRateLimitConfig.RateLimitValues.of(100, 60);
            var config = ServiceWebSocketRateLimitConfig.of(connValues, msgValues);

            assertTrue(config.connection().isPresent());
            assertTrue(config.message().isPresent());
        }

        @Test
        @DisplayName("of should handle null arguments")
        void ofShouldHandleNullArguments() {
            var config = ServiceWebSocketRateLimitConfig.of(null, null);

            assertTrue(config.connection().isEmpty());
            assertTrue(config.message().isEmpty());
        }

        @Test
        @DisplayName("defaults should create config with all empty values")
        void defaultsShouldCreateAllEmpty() {
            var config = ServiceWebSocketRateLimitConfig.defaults();

            assertTrue(config.connection().isEmpty());
            assertTrue(config.message().isEmpty());
        }
    }

    @Nested
    @DisplayName("RateLimitValues")
    class RateLimitValuesTests {

        @Nested
        @DisplayName("Compact constructor null coalescing")
        class ValuesNullCoalescing {

            @Test
            @DisplayName("should default null requestsPerWindow to empty Optional")
            void shouldDefaultNullRequestsPerWindow() {
                var values =
                        new ServiceWebSocketRateLimitConfig.RateLimitValues(null, Optional.of(60L), Optional.of(100L));

                assertTrue(values.requestsPerWindow().isEmpty());
                assertEquals(60L, values.windowSeconds().orElseThrow());
                assertEquals(100L, values.burstCapacity().orElseThrow());
            }

            @Test
            @DisplayName("should default null windowSeconds to empty Optional")
            void shouldDefaultNullWindowSeconds() {
                var values =
                        new ServiceWebSocketRateLimitConfig.RateLimitValues(Optional.of(100L), null, Optional.of(50L));

                assertTrue(values.windowSeconds().isEmpty());
                assertEquals(100L, values.requestsPerWindow().orElseThrow());
            }

            @Test
            @DisplayName("should default null burstCapacity to empty Optional")
            void shouldDefaultNullBurstCapacity() {
                var values =
                        new ServiceWebSocketRateLimitConfig.RateLimitValues(Optional.of(100L), Optional.of(60L), null);

                assertTrue(values.burstCapacity().isEmpty());
            }

            @Test
            @DisplayName("should default all null values to empty Optionals")
            void shouldDefaultAllNullValues() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(null, null, null);

                assertTrue(values.requestsPerWindow().isEmpty());
                assertTrue(values.windowSeconds().isEmpty());
                assertTrue(values.burstCapacity().isEmpty());
            }

            @Test
            @DisplayName("should preserve non-null values")
            void shouldPreserveNonNullValues() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(
                        Optional.of(100L), Optional.of(60L), Optional.of(150L));

                assertEquals(100L, values.requestsPerWindow().orElseThrow());
                assertEquals(60L, values.windowSeconds().orElseThrow());
                assertEquals(150L, values.burstCapacity().orElseThrow());
            }
        }

        @Nested
        @DisplayName("hasConfiguration")
        class ValuesHasConfiguration {

            @Test
            @DisplayName("should return false when all values are empty")
            void shouldReturnFalseWhenAllEmpty() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(
                        Optional.empty(), Optional.empty(), Optional.empty());

                assertFalse(values.hasConfiguration());
            }

            @Test
            @DisplayName("should return true when only requestsPerWindow is present")
            void shouldReturnTrueWhenOnlyRequestsPerWindow() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(
                        Optional.of(100L), Optional.empty(), Optional.empty());

                assertTrue(values.hasConfiguration());
            }

            @Test
            @DisplayName("should return true when only windowSeconds is present")
            void shouldReturnTrueWhenOnlyWindowSeconds() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(
                        Optional.empty(), Optional.of(60L), Optional.empty());

                assertTrue(values.hasConfiguration());
            }

            @Test
            @DisplayName("should return true when only burstCapacity is present")
            void shouldReturnTrueWhenOnlyBurstCapacity() {
                var values = new ServiceWebSocketRateLimitConfig.RateLimitValues(
                        Optional.empty(), Optional.empty(), Optional.of(150L));

                assertTrue(values.hasConfiguration());
            }
        }

        @Nested
        @DisplayName("Factory methods")
        class ValuesFactoryMethods {

            @Test
            @DisplayName("of(3 args) should set all values")
            void ofThreeArgsShouldSetAllValues() {
                var values = ServiceWebSocketRateLimitConfig.RateLimitValues.of(100, 60, 150);

                assertEquals(100L, values.requestsPerWindow().orElseThrow());
                assertEquals(60L, values.windowSeconds().orElseThrow());
                assertEquals(150L, values.burstCapacity().orElseThrow());
            }

            @Test
            @DisplayName("of(2 args) should set requests and window, leave burst empty")
            void ofTwoArgsShouldSetRequestsAndWindow() {
                var values = ServiceWebSocketRateLimitConfig.RateLimitValues.of(100, 60);

                assertEquals(100L, values.requestsPerWindow().orElseThrow());
                assertEquals(60L, values.windowSeconds().orElseThrow());
                assertTrue(values.burstCapacity().isEmpty());
            }
        }
    }
}
