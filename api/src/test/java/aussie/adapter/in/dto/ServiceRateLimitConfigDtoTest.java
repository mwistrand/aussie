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

import aussie.core.model.ratelimit.ServiceRateLimitConfig;

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
