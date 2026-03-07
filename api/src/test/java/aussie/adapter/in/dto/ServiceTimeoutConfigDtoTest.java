package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.timeout.ServiceTimeoutConfig;

@DisplayName("ServiceTimeoutConfigDto")
class ServiceTimeoutConfigDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert duration string to model")
        void shouldConvertDurationToModel() {
            var dto = new ServiceTimeoutConfigDto("PT30S");

            var model = dto.toModel();

            assertEquals(Optional.of(Duration.ofSeconds(30)), model.requestTimeout());
        }

        @Test
        @DisplayName("Should convert compound duration to model")
        void shouldConvertCompoundDurationToModel() {
            var dto = new ServiceTimeoutConfigDto("PT1M30S");

            var model = dto.toModel();

            assertEquals(Optional.of(Duration.ofSeconds(90)), model.requestTimeout());
        }

        @Test
        @DisplayName("Should convert null requestTimeout to empty Optional")
        void shouldConvertNullToEmpty() {
            var dto = new ServiceTimeoutConfigDto(null);

            var model = dto.toModel();

            assertTrue(model.requestTimeout().isEmpty());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model with duration to DTO")
        void shouldConvertModelWithDuration() {
            var model = ServiceTimeoutConfig.of(Duration.ofSeconds(30));

            var dto = ServiceTimeoutConfigDto.fromModel(model);

            assertEquals("PT30S", dto.requestTimeout());
        }

        @Test
        @DisplayName("Should convert model with empty duration to null field")
        void shouldConvertModelWithEmptyDuration() {
            var model = ServiceTimeoutConfig.defaults();

            var dto = ServiceTimeoutConfigDto.fromModel(model);

            assertNull(dto.requestTimeout());
        }

        @Test
        @DisplayName("Should return null for null model")
        void shouldReturnNullForNullModel() {
            assertNull(ServiceTimeoutConfigDto.fromModel(null));
        }
    }

    @Nested
    @DisplayName("Round-trip Conversion")
    class RoundTripTests {

        @Test
        @DisplayName("Should preserve value through round-trip")
        void shouldPreserveThroughRoundTrip() {
            var original = new ServiceTimeoutConfigDto("PT2M");

            var roundTripped = ServiceTimeoutConfigDto.fromModel(original.toModel());

            assertEquals("PT2M", roundTripped.requestTimeout());
        }
    }
}
