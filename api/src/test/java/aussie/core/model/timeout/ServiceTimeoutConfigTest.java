package aussie.core.model.timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceTimeoutConfig")
class ServiceTimeoutConfigTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should accept a positive duration")
        void shouldAcceptPositiveDuration() {
            var config = ServiceTimeoutConfig.of(Duration.ofSeconds(30));

            assertTrue(config.requestTimeout().isPresent());
            assertEquals(Duration.ofSeconds(30), config.requestTimeout().get());
        }

        @Test
        @DisplayName("Should reject zero duration")
        void shouldRejectZeroDuration() {
            assertThrows(IllegalArgumentException.class, () -> ServiceTimeoutConfig.of(Duration.ZERO));
        }

        @Test
        @DisplayName("Should reject negative duration")
        void shouldRejectNegativeDuration() {
            assertThrows(IllegalArgumentException.class, () -> ServiceTimeoutConfig.of(Duration.ofSeconds(-1)));
        }

        @Test
        @DisplayName("Should coalesce null requestTimeout to empty Optional")
        void shouldCoalesceNullToEmpty() {
            var config = new ServiceTimeoutConfig(null);

            assertTrue(config.requestTimeout().isEmpty());
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("of() should wrap the duration in an Optional")
        void ofShouldWrapDuration() {
            var config = ServiceTimeoutConfig.of(Duration.ofMinutes(2));

            assertEquals(Optional.of(Duration.ofMinutes(2)), config.requestTimeout());
        }

        @Test
        @DisplayName("defaults() should return empty requestTimeout")
        void defaultsShouldReturnEmpty() {
            var config = ServiceTimeoutConfig.defaults();

            assertTrue(config.requestTimeout().isEmpty());
        }
    }

    @Nested
    @DisplayName("hasConfiguration()")
    class HasConfigurationTests {

        @Test
        @DisplayName("Should return true when requestTimeout is present")
        void shouldReturnTrueWhenPresent() {
            assertTrue(ServiceTimeoutConfig.of(Duration.ofSeconds(10)).hasConfiguration());
        }

        @Test
        @DisplayName("Should return false when requestTimeout is empty")
        void shouldReturnFalseWhenEmpty() {
            assertFalse(ServiceTimeoutConfig.defaults().hasConfiguration());
        }
    }
}
