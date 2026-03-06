package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.dto.EndpointConfigDto.EndpointRateLimitConfigDto;
import aussie.adapter.in.dto.EndpointConfigDto.EndpointSamplingConfigDto;
import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.sampling.EndpointSamplingConfig;

@DisplayName("EndpointConfigDto Tests")
class EndpointConfigDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should use explicit authRequired=true when specified")
        void shouldUseExplicitAuthRequiredTrue() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true, null, null, null, null);

            var model = dto.toModel();

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should use explicit authRequired=false when specified")
        void shouldUseExplicitAuthRequiredFalse() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false, null, null, null, null);

            var model = dto.toModel();

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should default to false when authRequired is null and no default provided")
        void shouldDefaultToFalseWhenAuthRequiredNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);

            var model = dto.toModel();

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should default visibility to PUBLIC when not specified")
        void shouldDefaultVisibilityToPublic() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), null, null, null, null, null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointVisibility.PUBLIC, model.visibility());
        }

        @Test
        @DisplayName("Should parse visibility case-insensitively")
        void shouldParseVisibilityCaseInsensitively() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "private", null, null, null, null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointVisibility.PRIVATE, model.visibility());
        }

        @Test
        @DisplayName("Should preserve pathRewrite when specified")
        void shouldPreservePathRewrite() {
            var dto = new EndpointConfigDto(
                    "/api/test", Set.of("GET"), "PUBLIC", "/rewritten", null, null, null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.of("/rewritten"), model.pathRewrite());
        }

        @Test
        @DisplayName("Should default type to HTTP when not specified")
        void shouldDefaultTypeToHttp() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointType.HTTP, model.type());
        }

        @Test
        @DisplayName("Should parse WEBSOCKET type")
        void shouldParseWebSocketType() {
            var dto = new EndpointConfigDto("/ws/echo", null, "PUBLIC", null, false, "WEBSOCKET", null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointType.WEBSOCKET, model.type());
        }

        @Test
        @DisplayName("Should default methods to GET for WEBSOCKET endpoints when not specified")
        void shouldDefaultMethodsToGetForWebSocket() {
            var dto = new EndpointConfigDto("/ws/echo", null, "PUBLIC", null, false, "WEBSOCKET", null, null, null);

            var model = dto.toModel();

            assertEquals(Set.of("GET"), model.methods());
        }

        @Test
        @DisplayName("Should parse type case-insensitively")
        void shouldParseTypeCaseInsensitively() {
            var dto = new EndpointConfigDto("/ws/echo", null, "PUBLIC", null, false, "websocket", null, null, null);

            var model = dto.toModel();

            assertEquals(EndpointType.WEBSOCKET, model.type());
        }

        @Test
        @DisplayName("Should preserve audience when specified")
        void shouldPreserveAudience() {
            var dto = new EndpointConfigDto(
                    "/api/test", Set.of("GET"), "PUBLIC", null, true, null, "my-service", null, null);

            var model = dto.toModel();

            assertEquals(Optional.of("my-service"), model.audience());
        }

        @Test
        @DisplayName("Should default audience to empty when not specified")
        void shouldDefaultAudienceToEmpty() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true, null, null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.audience());
        }
    }

    @Nested
    @DisplayName("toModel(boolean defaultAuthRequired)")
    class ToModelWithDefaultTests {

        @Test
        @DisplayName("Should use explicit authRequired=true even when default is false")
        void shouldUseExplicitTrueOverDefaultFalse() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true, null, null, null, null);

            var model = dto.toModel(false);

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should use explicit authRequired=false even when default is true")
        void shouldUseExplicitFalseOverDefaultTrue() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false, null, null, null, null);

            var model = dto.toModel(true);

            assertFalse(model.authRequired());
        }

        @Test
        @DisplayName("Should inherit default=true when authRequired is null")
        void shouldInheritDefaultTrueWhenNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);

            var model = dto.toModel(true);

            assertTrue(model.authRequired());
        }

        @Test
        @DisplayName("Should inherit default=false when authRequired is null")
        void shouldInheritDefaultFalseWhenNull() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);

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
                    "/api/test",
                    Set.of("GET", "POST"),
                    EndpointVisibility.PRIVATE,
                    Optional.of("/rewritten"),
                    true,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            var dto = EndpointConfigDto.fromModel(model);

            assertEquals("/api/test", dto.path());
            assertEquals(Set.of("GET", "POST"), dto.methods());
            assertEquals("PRIVATE", dto.visibility());
            assertEquals("/rewritten", dto.pathRewrite());
            assertTrue(dto.authRequired());
            assertEquals("HTTP", dto.type());
        }

        @Test
        @DisplayName("Should handle empty pathRewrite")
        void shouldHandleEmptyPathRewrite() {
            var model = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            var dto = EndpointConfigDto.fromModel(model);

            assertEquals(null, dto.pathRewrite());
            assertFalse(dto.authRequired());
        }

        @Test
        @DisplayName("Should convert WEBSOCKET type to DTO")
        void shouldConvertWebSocketTypeToDto() {
            var model = new EndpointConfig(
                    "/ws/echo",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.WEBSOCKET,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            var dto = EndpointConfigDto.fromModel(model);

            assertEquals("WEBSOCKET", dto.type());
        }
    }

    @Nested
    @DisplayName("EndpointRateLimitConfigDto")
    class EndpointRateLimitConfigDtoTests {

        @Test
        @DisplayName("toModel should wrap values in optionals")
        void toModelShouldWrapValuesInOptionals() {
            var dto = new EndpointRateLimitConfigDto(100L, 60L, 50L);

            var model = dto.toModel();

            assertEquals(Optional.of(100L), model.requestsPerWindow());
            assertEquals(Optional.of(60L), model.windowSeconds());
            assertEquals(Optional.of(50L), model.burstCapacity());
        }

        @Test
        @DisplayName("toModel should handle null values")
        void toModelShouldHandleNullValues() {
            var dto = new EndpointRateLimitConfigDto(null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.requestsPerWindow());
            assertEquals(Optional.empty(), model.windowSeconds());
            assertEquals(Optional.empty(), model.burstCapacity());
        }

        @Test
        @DisplayName("fromModel should unwrap optionals")
        void fromModelShouldUnwrapOptionals() {
            var model = new EndpointRateLimitConfig(Optional.of(200L), Optional.of(30L), Optional.of(100L));

            var dto = EndpointRateLimitConfigDto.fromModel(model);

            assertEquals(200L, dto.requestsPerWindow());
            assertEquals(30L, dto.windowSeconds());
            assertEquals(100L, dto.burstCapacity());
        }

        @Test
        @DisplayName("fromModel should return nulls for empty optionals")
        void fromModelShouldReturnNullsForEmptyOptionals() {
            var model = new EndpointRateLimitConfig(Optional.empty(), Optional.empty(), Optional.empty());

            var dto = EndpointRateLimitConfigDto.fromModel(model);

            assertNull(dto.requestsPerWindow());
            assertNull(dto.windowSeconds());
            assertNull(dto.burstCapacity());
        }
    }

    @Nested
    @DisplayName("EndpointSamplingConfigDto")
    class EndpointSamplingConfigDtoTests {

        @Test
        @DisplayName("toModel should wrap sampling rate in optional")
        void toModelShouldWrapSamplingRate() {
            var dto = new EndpointSamplingConfigDto(0.5);

            var model = dto.toModel();

            assertEquals(Optional.of(0.5), model.samplingRate());
        }

        @Test
        @DisplayName("toModel should handle null sampling rate")
        void toModelShouldHandleNull() {
            var dto = new EndpointSamplingConfigDto(null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.samplingRate());
        }

        @Test
        @DisplayName("fromModel should unwrap sampling rate")
        void fromModelShouldUnwrapSamplingRate() {
            var model = new EndpointSamplingConfig(Optional.of(0.75));

            var dto = EndpointSamplingConfigDto.fromModel(model);

            assertEquals(0.75, dto.samplingRate());
        }

        @Test
        @DisplayName("fromModel should return null for empty optional")
        void fromModelShouldReturnNullForEmpty() {
            var model = new EndpointSamplingConfig(Optional.empty());

            var dto = EndpointSamplingConfigDto.fromModel(model);

            assertNull(dto.samplingRate());
        }
    }

    @Nested
    @DisplayName("toModel with rateLimitConfig and samplingConfig")
    class ToModelWithSubConfigTests {

        @Test
        @DisplayName("Should convert endpoint with rate limit config")
        void shouldConvertWithRateLimitConfig() {
            var rateLimitDto = new EndpointRateLimitConfigDto(100L, 60L, null);
            var dto = new EndpointConfigDto(
                    "/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, rateLimitDto, null);

            var model = dto.toModel();

            assertTrue(model.rateLimitConfig().isPresent());
            assertEquals(Optional.of(100L), model.rateLimitConfig().get().requestsPerWindow());
        }

        @Test
        @DisplayName("Should convert endpoint with sampling config")
        void shouldConvertWithSamplingConfig() {
            var samplingDto = new EndpointSamplingConfigDto(0.5);
            var dto = new EndpointConfigDto(
                    "/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, samplingDto);

            var model = dto.toModel();

            assertTrue(model.samplingConfig().isPresent());
            assertEquals(Optional.of(0.5), model.samplingConfig().get().samplingRate());
        }
    }

    @Nested
    @DisplayName("fromModel with rateLimitConfig and samplingConfig")
    class FromModelWithSubConfigTests {

        @Test
        @DisplayName("Should convert model with rate limit config to DTO")
        void shouldConvertWithRateLimitConfig() {
            var rateLimitModel = new EndpointRateLimitConfig(Optional.of(100L), Optional.of(60L), Optional.empty());
            var model = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.of(rateLimitModel),
                    Optional.empty(),
                    Optional.empty());

            var dto = EndpointConfigDto.fromModel(model);

            assertNotNull(dto.rateLimitConfig());
            assertEquals(100L, dto.rateLimitConfig().requestsPerWindow());
            assertEquals(60L, dto.rateLimitConfig().windowSeconds());
            assertNull(dto.rateLimitConfig().burstCapacity());
        }

        @Test
        @DisplayName("Should convert model with sampling config to DTO")
        void shouldConvertWithSamplingConfig() {
            var samplingModel = new EndpointSamplingConfig(Optional.of(0.25));
            var model = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.of(samplingModel),
                    Optional.empty());

            var dto = EndpointConfigDto.fromModel(model);

            assertNotNull(dto.samplingConfig());
            assertEquals(0.25, dto.samplingConfig().samplingRate());
        }
    }

    @Nested
    @DisplayName("Round-trip conversion")
    class RoundTripTests {

        @Test
        @DisplayName("Should preserve all fields through round-trip conversion")
        void shouldPreserveFieldsThroughRoundTrip() {
            var original = new EndpointConfigDto(
                    "/api/users",
                    Set.of("GET", "POST"),
                    "PRIVATE",
                    "/v2/users",
                    true,
                    "HTTP",
                    "my-service",
                    null,
                    null);

            var model = original.toModel();
            var roundTripped = EndpointConfigDto.fromModel(model);

            assertEquals(original.path(), roundTripped.path());
            assertEquals(original.methods(), roundTripped.methods());
            assertEquals(original.visibility(), roundTripped.visibility());
            assertEquals(original.pathRewrite(), roundTripped.pathRewrite());
            assertEquals(original.authRequired(), roundTripped.authRequired());
            assertEquals(original.type(), roundTripped.type());
            assertEquals(original.audience(), roundTripped.audience());
        }

        @Test
        @DisplayName("Should preserve WEBSOCKET type through round-trip conversion")
        void shouldPreserveWebSocketTypeThroughRoundTrip() {
            var original =
                    new EndpointConfigDto("/ws/chat", null, "PRIVATE", null, true, "WEBSOCKET", null, null, null);

            var model = original.toModel();
            var roundTripped = EndpointConfigDto.fromModel(model);

            assertEquals("WEBSOCKET", roundTripped.type());
            assertEquals(EndpointType.WEBSOCKET, model.type());
        }

        @Test
        @DisplayName("Should preserve audience through round-trip conversion")
        void shouldPreserveAudienceThroughRoundTrip() {
            var original = new EndpointConfigDto(
                    "/api/test", Set.of("GET"), "PUBLIC", null, true, "HTTP", "test-audience", null, null);

            var model = original.toModel();
            var roundTripped = EndpointConfigDto.fromModel(model);

            assertEquals("test-audience", roundTripped.audience());
            assertEquals(Optional.of("test-audience"), model.audience());
        }

        @Test
        @DisplayName("Should handle null audience through round-trip conversion")
        void shouldHandleNullAudienceThroughRoundTrip() {
            var original =
                    new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, true, "HTTP", null, null, null);

            var model = original.toModel();
            var roundTripped = EndpointConfigDto.fromModel(model);

            assertNull(roundTripped.audience());
            assertEquals(Optional.empty(), model.audience());
        }
    }
}
