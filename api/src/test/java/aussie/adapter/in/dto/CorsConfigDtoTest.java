package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.common.CorsConfig;

@DisplayName("CorsConfigDto Tests")
class CorsConfigDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert all fields to model")
        void shouldConvertAllFieldsToModel() {
            var dto = new CorsConfigDto(
                    List.of("https://example.com"),
                    Set.of("GET", "POST"),
                    Set.of("Authorization"),
                    Set.of("X-Custom"),
                    true,
                    3600L);

            var model = dto.toModel();

            assertEquals(List.of("https://example.com"), model.allowedOrigins());
            assertEquals(Set.of("GET", "POST"), model.allowedMethods());
            assertEquals(Set.of("Authorization"), model.allowedHeaders());
            assertEquals(Set.of("X-Custom"), model.exposedHeaders());
            assertTrue(model.allowCredentials());
            assertEquals(Optional.of(3600L), model.maxAge());
        }

        @Test
        @DisplayName("Should default null fields to empty collections and false")
        void shouldDefaultNullFields() {
            var dto = new CorsConfigDto(null, null, null, null, null, null);

            var model = dto.toModel();

            assertEquals(List.of(), model.allowedOrigins());
            assertEquals(Set.of(), model.allowedMethods());
            assertEquals(Set.of(), model.allowedHeaders());
            assertEquals(Set.of(), model.exposedHeaders());
            assertFalse(model.allowCredentials());
            assertEquals(Optional.empty(), model.maxAge());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model to DTO")
        void shouldConvertModelToDto() {
            var model = new CorsConfig(
                    List.of("https://example.com"),
                    Set.of("GET"),
                    Set.of("Content-Type"),
                    Set.of("X-Request-Id"),
                    true,
                    Optional.of(7200L));

            var dto = CorsConfigDto.fromModel(model);

            assertEquals(List.of("https://example.com"), dto.allowedOrigins());
            assertEquals(Set.of("GET"), dto.allowedMethods());
            assertEquals(Set.of("Content-Type"), dto.allowedHeaders());
            assertEquals(Set.of("X-Request-Id"), dto.exposedHeaders());
            assertTrue(dto.allowCredentials());
            assertEquals(7200L, dto.maxAge());
        }

        @Test
        @DisplayName("Should return null maxAge when model has empty optional")
        void shouldReturnNullMaxAgeWhenEmpty() {
            var model = new CorsConfig(List.of(), Set.of(), Set.of(), Set.of(), false, Optional.empty());

            var dto = CorsConfigDto.fromModel(model);

            assertNull(dto.maxAge());
        }

        @Test
        @DisplayName("Should return null for null model")
        void shouldReturnNullForNullModel() {
            assertNull(CorsConfigDto.fromModel(null));
        }
    }
}
