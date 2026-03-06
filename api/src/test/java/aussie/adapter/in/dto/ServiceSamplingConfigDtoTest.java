package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.sampling.ServiceSamplingConfig;

@DisplayName("ServiceSamplingConfigDto Tests")
class ServiceSamplingConfigDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert sampling rate to model")
        void shouldConvertSamplingRateToModel() {
            var dto = new ServiceSamplingConfigDto(0.5);

            var model = dto.toModel();

            assertEquals(Optional.of(0.5), model.samplingRate());
        }

        @Test
        @DisplayName("Should handle null sampling rate")
        void shouldHandleNullSamplingRate() {
            var dto = new ServiceSamplingConfigDto(null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.samplingRate());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model to DTO")
        void shouldConvertModelToDto() {
            var model = new ServiceSamplingConfig(Optional.of(0.75));

            var dto = ServiceSamplingConfigDto.fromModel(model);

            assertNotNull(dto);
            assertEquals(0.75, dto.samplingRate());
        }

        @Test
        @DisplayName("Should return null sampling rate when model has empty optional")
        void shouldReturnNullWhenEmpty() {
            var model = new ServiceSamplingConfig(Optional.empty());

            var dto = ServiceSamplingConfigDto.fromModel(model);

            assertNotNull(dto);
            assertNull(dto.samplingRate());
        }

        @Test
        @DisplayName("Should return null for null model")
        void shouldReturnNullForNullModel() {
            assertNull(ServiceSamplingConfigDto.fromModel(null));
        }
    }
}
