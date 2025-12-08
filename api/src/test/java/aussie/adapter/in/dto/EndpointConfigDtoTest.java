package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;

@DisplayName("EndpointConfigDto Tests")
class EndpointConfigDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should use explicit authRequired=true when specified")
        void shouldUseExplicitAuthRequiredTrue() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true);

            var model = dto.toModel();

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should use explicit authRequired=false when specified")
        void shouldUseExplicitAuthRequiredFalse() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false);

            var model = dto.toModel();

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should default to false when authRequired is null and no default provided")
        void shouldDefaultToFalseWhenAuthRequiredNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null);

            var model = dto.toModel();

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should default visibility to PUBLIC when not specified")
        void shouldDefaultVisibilityToPublic() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointVisibility.PUBLIC, model.visibility());
        }

        @Test
        @DisplayName("Should parse visibility case-insensitively")
        void shouldParseVisibilityCaseInsensitively() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "private", null, null);

            var model = dto.toModel();

            assertEquals(EndpointVisibility.PRIVATE, model.visibility());
        }

        @Test
        @DisplayName("Should preserve pathRewrite when specified")
        void shouldPreservePathRewrite() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", "/rewritten", null);

            var model = dto.toModel();

            assertEquals(Optional.of("/rewritten"), model.pathRewrite());
        }
    }

    @Nested
    @DisplayName("toModel(boolean defaultAuthRequired)")
    class ToModelWithDefaultTests {

        @Test
        @DisplayName("Should use explicit authRequired=true even when default is false")
        void shouldUseExplicitTrueOverDefaultFalse() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true);

            var model = dto.toModel(false);

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should use explicit authRequired=false even when default is true")
        void shouldUseExplicitFalseOverDefaultTrue() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false);

            var model = dto.toModel(true);

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should inherit default=true when authRequired is null")
        void shouldInheritDefaultTrueWhenNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null);

            var model = dto.toModel(true);

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should inherit default=false when authRequired is null")
        void shouldInheritDefaultFalseWhenNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null);

            var model = dto.toModel(false);

            assertFalse(model.authRequired());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should correctly convert model to DTO")
        void shouldConvertModelToDto() {
            var model = new EndpointConfig(
                    "/api/test", Set.of("GET", "POST"), EndpointVisibility.PRIVATE, Optional.of("/rewritten"), true);

            var dto = EndpointConfigDto.fromModel(model);

            assertEquals("/api/test", dto.path());
            assertEquals(Set.of("GET", "POST"), dto.methods());
            assertEquals("PRIVATE", dto.visibility());
            assertEquals("/rewritten", dto.pathRewrite());
            assertTrue(dto.authRequired());
        }

        @Test
        @DisplayName("Should handle empty pathRewrite")
        void shouldHandleEmptyPathRewrite() {
            var model =
                    new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);

            var dto = EndpointConfigDto.fromModel(model);

            assertEquals(null, dto.pathRewrite());
            assertFalse(dto.authRequired());
        }
    }

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTripTests {

        @Test
        @DisplayName("Should preserve all fields through round-trip conversion")
        void shouldPreserveFieldsThroughRoundTrip() {
            var original = new EndpointConfigDto("/api/users", Set.of("GET", "POST"), "PRIVATE", "/v2/users", true);

            var model = original.toModel();
            var roundTripped = EndpointConfigDto.fromModel(model);

            assertEquals(original.path(), roundTripped.path());
            assertEquals(original.methods(), roundTripped.methods());
            assertEquals(original.visibility(), roundTripped.visibility());
            assertEquals(original.pathRewrite(), roundTripped.pathRewrite());
            assertEquals(original.authRequired(), roundTripped.authRequired());
        }
    }
}
