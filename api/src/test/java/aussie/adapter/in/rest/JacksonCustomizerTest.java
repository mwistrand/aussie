package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JacksonCustomizer")
class JacksonCustomizerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        var customizer = new JacksonCustomizer();
        customizer.customize(objectMapper);
    }

    @Nested
    @DisplayName("Optional serialization")
    class OptionalSerialization {

        @Test
        @DisplayName("serializes present Optional as value")
        void serializesPresentOptional() throws JsonProcessingException {
            var result = objectMapper.writeValueAsString(Optional.of("hello"));

            assertEquals("\"hello\"", result);
        }

        @Test
        @DisplayName("serializes empty Optional as null")
        void serializesEmptyOptionalAsNull() throws JsonProcessingException {
            var result = objectMapper.writeValueAsString(Optional.empty());

            assertEquals("null", result);
        }

        @Test
        @DisplayName("deserializes value into Optional")
        void deserializesValueIntoOptional() throws JsonProcessingException {
            record OptionalHolder(Optional<String> name) {}

            var result = objectMapper.readValue("{\"name\": \"test\"}", OptionalHolder.class);

            assertTrue(result.name().isPresent());
            assertEquals("test", result.name().get());
        }
    }

    @Nested
    @DisplayName("Unknown properties")
    class UnknownProperties {

        @Test
        @DisplayName("does not throw on unknown properties during deserialization")
        void doesNotThrowOnUnknownProperties() {
            record SimpleRecord(String name) {}

            assertDoesNotThrow(
                    () -> objectMapper.readValue("{\"name\": \"test\", \"unknown\": \"value\"}", SimpleRecord.class));
        }

        @Test
        @DisplayName("correctly deserializes known properties ignoring unknown ones")
        void correctlyDeserializesKnownProperties() throws JsonProcessingException {
            record SimpleRecord(String name) {}

            var result = objectMapper.readValue(
                    "{\"name\": \"test\", \"extra\": 42, \"another\": true}", SimpleRecord.class);

            assertEquals("test", result.name());
        }
    }
}
