package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.dto.ServiceRateLimitConfigDto.RateLimitValuesDto;
import aussie.adapter.in.dto.ServiceRateLimitConfigDto.WebSocketRateLimitConfigDto;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.ratelimit.ServiceWebSocketRateLimitConfig;
import aussie.core.model.ratelimit.ServiceWebSocketRateLimitConfig.RateLimitValues;

@DisplayName("ServiceRateLimitConfigDto Tests")
class ServiceRateLimitConfigDtoTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert all fields to model")
        void shouldConvertAllFieldsToModel() {
            var dto = new ServiceRateLimitConfigDto(100L, 60L, 50L, null);

            var model = dto.toModel();

            assertEquals(Optional.of(100L), model.requestsPerWindow());
            assertEquals(Optional.of(60L), model.windowSeconds());
            assertEquals(Optional.of(50L), model.burstCapacity());
        }

        @Test
        @DisplayName("Should handle null values")
        void shouldHandleNullValues() {
            var dto = new ServiceRateLimitConfigDto(100L, null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.of(100L), model.requestsPerWindow());
            assertEquals(Optional.empty(), model.windowSeconds());
            assertEquals(Optional.empty(), model.burstCapacity());
        }

        @Test
        @DisplayName("Should handle all nulls")
        void shouldHandleAllNulls() {
            var dto = new ServiceRateLimitConfigDto(null, null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.requestsPerWindow());
            assertEquals(Optional.empty(), model.windowSeconds());
            assertEquals(Optional.empty(), model.burstCapacity());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert all fields from model")
        void shouldConvertAllFieldsFromModel() {
            var model = ServiceRateLimitConfig.of(100L, 60L, 50L);

            var dto = ServiceRateLimitConfigDto.fromModel(model);

            assertNotNull(dto);
            assertEquals(100L, dto.requestsPerWindow());
            assertEquals(60L, dto.windowSeconds());
            assertEquals(50L, dto.burstCapacity());
            assertNull(dto.websocket()); // Not stored in model yet
        }

        @Test
        @DisplayName("Should handle empty optionals from model")
        void shouldHandleEmptyOptionals() {
            var model = ServiceRateLimitConfig.defaults();

            var dto = ServiceRateLimitConfigDto.fromModel(model);

            assertNotNull(dto);
            assertNull(dto.requestsPerWindow());
            assertNull(dto.windowSeconds());
            assertNull(dto.burstCapacity());
        }

        @Test
        @DisplayName("Should return null for null model")
        void shouldReturnNullForNullModel() {
            var dto = ServiceRateLimitConfigDto.fromModel(null);

            assertNull(dto);
        }
    }

    @Nested
    @DisplayName("JSON Serialization")
    class JsonSerializationTests {

        @Test
        @DisplayName("Should deserialize basic rate limit config")
        void shouldDeserializeBasicConfig() throws Exception {
            var json =
                    """
                    {
                        "requestsPerWindow": 100,
                        "windowSeconds": 60,
                        "burstCapacity": 50
                    }
                    """;

            var dto = OBJECT_MAPPER.readValue(json, ServiceRateLimitConfigDto.class);

            assertEquals(100L, dto.requestsPerWindow());
            assertEquals(60L, dto.windowSeconds());
            assertEquals(50L, dto.burstCapacity());
        }

        @Test
        @DisplayName("Should deserialize config with websocket settings")
        void shouldDeserializeWithWebsocket() throws Exception {
            var json =
                    """
                    {
                        "requestsPerWindow": 100,
                        "burstCapacity": 5,
                        "websocket": {
                            "connection": {
                                "requestsPerWindow": 5,
                                "burstCapacity": 3
                            },
                            "message": {
                                "requestsPerWindow": 200,
                                "burstCapacity": 100
                            }
                        }
                    }
                    """;

            var dto = OBJECT_MAPPER.readValue(json, ServiceRateLimitConfigDto.class);

            assertEquals(100L, dto.requestsPerWindow());
            assertEquals(5L, dto.burstCapacity());
            assertNotNull(dto.websocket());
            assertNotNull(dto.websocket().connection());
            assertEquals(5L, dto.websocket().connection().requestsPerWindow());
            assertEquals(3L, dto.websocket().connection().burstCapacity());
            assertNotNull(dto.websocket().message());
            assertEquals(200L, dto.websocket().message().requestsPerWindow());
            assertEquals(100L, dto.websocket().message().burstCapacity());
        }

        @Test
        @DisplayName("Should serialize to JSON and back")
        void shouldSerializeAndDeserialize() throws Exception {
            var original = new ServiceRateLimitConfigDto(100L, 60L, 50L, null);

            var json = OBJECT_MAPPER.writeValueAsString(original);
            var deserialized = OBJECT_MAPPER.readValue(json, ServiceRateLimitConfigDto.class);

            assertEquals(original.requestsPerWindow(), deserialized.requestsPerWindow());
            assertEquals(original.windowSeconds(), deserialized.windowSeconds());
            assertEquals(original.burstCapacity(), deserialized.burstCapacity());
        }
    }

    @Nested
    @DisplayName("WebSocketRateLimitConfigDto")
    class WebSocketRateLimitConfigDtoTests {

        @Test
        @DisplayName("toModel should convert connection and message configs")
        void toModelShouldConvertConfigs() {
            var connection = new RateLimitValuesDto(10L, 60L, 5L);
            var message = new RateLimitValuesDto(200L, 60L, 100L);
            var dto = new WebSocketRateLimitConfigDto(connection, message);

            var model = dto.toModel();

            assertTrue(model.connection().isPresent());
            assertEquals(Optional.of(10L), model.connection().get().requestsPerWindow());
            assertTrue(model.message().isPresent());
            assertEquals(Optional.of(200L), model.message().get().requestsPerWindow());
        }

        @Test
        @DisplayName("toModel should handle null connection and message")
        void toModelShouldHandleNulls() {
            var dto = new WebSocketRateLimitConfigDto(null, null);

            var model = dto.toModel();

            assertTrue(model.connection().isEmpty());
            assertTrue(model.message().isEmpty());
        }

        @Test
        @DisplayName("fromModel should convert model to DTO")
        void fromModelShouldConvertModelToDto() {
            var connValues = RateLimitValues.of(10L, 60L, 5L);
            var msgValues = RateLimitValues.of(200L, 60L, 100L);
            var model = ServiceWebSocketRateLimitConfig.of(connValues, msgValues);

            var dto = WebSocketRateLimitConfigDto.fromModel(model);

            assertNotNull(dto);
            assertNotNull(dto.connection());
            assertEquals(10L, dto.connection().requestsPerWindow());
            assertNotNull(dto.message());
            assertEquals(200L, dto.message().requestsPerWindow());
        }

        @Test
        @DisplayName("fromModel should return null for null model")
        void fromModelShouldReturnNullForNullModel() {
            assertNull(WebSocketRateLimitConfigDto.fromModel(null));
        }

        @Test
        @DisplayName("fromModel should handle empty optionals in model")
        void fromModelShouldHandleEmptyOptionals() {
            var model = ServiceWebSocketRateLimitConfig.defaults();

            var dto = WebSocketRateLimitConfigDto.fromModel(model);

            assertNotNull(dto);
            assertNull(dto.connection());
            assertNull(dto.message());
        }
    }

    @Nested
    @DisplayName("RateLimitValuesDto")
    class RateLimitValuesDtoTests {

        @Test
        @DisplayName("toModel should wrap values in optionals")
        void toModelShouldWrapValues() {
            var dto = new RateLimitValuesDto(50L, 30L, 25L);

            var model = dto.toModel();

            assertEquals(Optional.of(50L), model.requestsPerWindow());
            assertEquals(Optional.of(30L), model.windowSeconds());
            assertEquals(Optional.of(25L), model.burstCapacity());
        }

        @Test
        @DisplayName("toModel should handle null values")
        void toModelShouldHandleNulls() {
            var dto = new RateLimitValuesDto(null, null, null);

            var model = dto.toModel();

            assertEquals(Optional.empty(), model.requestsPerWindow());
            assertEquals(Optional.empty(), model.windowSeconds());
            assertEquals(Optional.empty(), model.burstCapacity());
        }

        @Test
        @DisplayName("fromModel should unwrap optionals")
        void fromModelShouldUnwrapOptionals() {
            var model = RateLimitValues.of(50L, 30L, 25L);

            var dto = RateLimitValuesDto.fromModel(model);

            assertNotNull(dto);
            assertEquals(50L, dto.requestsPerWindow());
            assertEquals(30L, dto.windowSeconds());
            assertEquals(25L, dto.burstCapacity());
        }

        @Test
        @DisplayName("fromModel should return null for null model")
        void fromModelShouldReturnNullForNullModel() {
            assertNull(RateLimitValuesDto.fromModel(null));
        }

        @Test
        @DisplayName("fromModel should return nulls for empty optionals")
        void fromModelShouldReturnNullsForEmptyOptionals() {
            var model = new RateLimitValues(Optional.empty(), Optional.empty(), Optional.empty());

            var dto = RateLimitValuesDto.fromModel(model);

            assertNotNull(dto);
            assertNull(dto.requestsPerWindow());
            assertNull(dto.windowSeconds());
            assertNull(dto.burstCapacity());
        }
    }

    @Nested
    @DisplayName("toModel with websocket config")
    class ToModelWithWebSocketTests {

        @Test
        @DisplayName("Should convert DTO with websocket config to model")
        void shouldConvertWithWebSocketConfig() {
            var connection = new RateLimitValuesDto(10L, 60L, null);
            var wsDto = new WebSocketRateLimitConfigDto(connection, null);
            var dto = new ServiceRateLimitConfigDto(100L, 60L, 50L, wsDto);

            var model = dto.toModel();

            assertTrue(model.websocket().isPresent());
            assertTrue(model.websocket().get().connection().isPresent());
            assertEquals(
                    Optional.of(10L), model.websocket().get().connection().get().requestsPerWindow());
        }
    }

    @Nested
    @DisplayName("fromModel with websocket config")
    class FromModelWithWebSocketTests {

        @Test
        @DisplayName("Should convert model with websocket config to DTO")
        void shouldConvertWithWebSocketConfig() {
            var connValues = RateLimitValues.of(10L, 60L, 5L);
            var wsModel = ServiceWebSocketRateLimitConfig.of(connValues, null);
            var model = new ServiceRateLimitConfig(
                    Optional.of(100L), Optional.of(60L), Optional.of(50L), Optional.of(wsModel));

            var dto = ServiceRateLimitConfigDto.fromModel(model);

            assertNotNull(dto);
            assertNotNull(dto.websocket());
            assertNotNull(dto.websocket().connection());
            assertEquals(10L, dto.websocket().connection().requestsPerWindow());
        }
    }

    @Nested
    @DisplayName("ServiceRateLimitConfig JSON Serialization")
    class ModelSerializationTests {

        @Test
        @DisplayName("Should serialize model with Optional fields")
        void shouldSerializeModelWithOptional() throws Exception {
            var model = ServiceRateLimitConfig.of(100L, 60L, 50L);

            var json = OBJECT_MAPPER.writeValueAsString(model);
            var deserialized = OBJECT_MAPPER.readValue(json, ServiceRateLimitConfig.class);

            assertEquals(model.requestsPerWindow(), deserialized.requestsPerWindow());
            assertEquals(model.windowSeconds(), deserialized.windowSeconds());
            assertEquals(model.burstCapacity(), deserialized.burstCapacity());
            assertTrue(deserialized.hasConfiguration());
        }

        @Test
        @DisplayName("Should serialize and deserialize default model")
        void shouldSerializeDefaultModel() throws Exception {
            var model = ServiceRateLimitConfig.defaults();

            var json = OBJECT_MAPPER.writeValueAsString(model);
            var deserialized = OBJECT_MAPPER.readValue(json, ServiceRateLimitConfig.class);

            assertEquals(Optional.empty(), deserialized.requestsPerWindow());
            assertEquals(Optional.empty(), deserialized.windowSeconds());
            assertEquals(Optional.empty(), deserialized.burstCapacity());
        }
    }
}
