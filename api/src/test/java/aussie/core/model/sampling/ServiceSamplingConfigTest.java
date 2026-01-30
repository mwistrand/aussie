package aussie.core.model.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceSamplingConfig")
class ServiceSamplingConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new Jdk8Module());

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with valid rate")
        void shouldCreateWithValidRate() {
            var config = ServiceSamplingConfig.of(0.5);

            assertTrue(config.samplingRate().isPresent());
            assertEquals(0.5, config.samplingRate().get());
        }

        @Test
        @DisplayName("should reject rate below 0.0")
        void shouldRejectRateBelowZero() {
            assertThrows(IllegalArgumentException.class, () -> ServiceSamplingConfig.of(-0.1));
        }

        @Test
        @DisplayName("should reject rate above 1.0")
        void shouldRejectRateAboveOne() {
            assertThrows(IllegalArgumentException.class, () -> ServiceSamplingConfig.of(1.1));
        }

        @Test
        @DisplayName("should accept boundary values")
        void shouldAcceptBoundaryValues() {
            var zero = ServiceSamplingConfig.of(0.0);
            var one = ServiceSamplingConfig.of(1.0);

            assertEquals(0.0, zero.samplingRate().get());
            assertEquals(1.0, one.samplingRate().get());
        }

        @Test
        @DisplayName("should handle null samplingRate")
        void shouldHandleNullSamplingRate() {
            var config = new ServiceSamplingConfig(null);

            assertFalse(config.samplingRate().isPresent());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("noSampling should create config with rate 1.0")
        void noSamplingShouldCreateConfigWithRateOne() {
            var config = ServiceSamplingConfig.noSampling();

            assertTrue(config.samplingRate().isPresent());
            assertEquals(1.0, config.samplingRate().get());
        }

        @Test
        @DisplayName("defaults should create empty config")
        void defaultsShouldCreateEmptyConfig() {
            var config = ServiceSamplingConfig.defaults();

            assertFalse(config.samplingRate().isPresent());
        }

        @Test
        @DisplayName("of should create config with specified rate")
        void ofShouldCreateConfigWithSpecifiedRate() {
            var config = ServiceSamplingConfig.of(0.25);

            assertTrue(config.samplingRate().isPresent());
            assertEquals(0.25, config.samplingRate().get());
        }
    }

    @Nested
    @DisplayName("hasConfiguration")
    class HasConfiguration {

        @Test
        @DisplayName("should return true when rate is present")
        void shouldReturnTrueWhenRateIsPresent() {
            var config = ServiceSamplingConfig.of(0.5);

            assertTrue(config.hasConfiguration());
        }

        @Test
        @DisplayName("should return false when rate is empty")
        void shouldReturnFalseWhenRateIsEmpty() {
            var config = ServiceSamplingConfig.defaults();

            assertFalse(config.hasConfiguration());
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        @Test
        @DisplayName("should serialize config with rate")
        void shouldSerializeConfigWithRate() throws JsonProcessingException {
            var config = ServiceSamplingConfig.of(0.5);

            var json = objectMapper.writeValueAsString(config);

            assertTrue(json.contains("\"samplingRate\""));
            assertTrue(json.contains("0.5"));
        }

        @Test
        @DisplayName("should deserialize config with rate")
        void shouldDeserializeConfigWithRate() throws JsonProcessingException {
            var json = "{\"samplingRate\":0.75}";

            var config = objectMapper.readValue(json, ServiceSamplingConfig.class);

            assertTrue(config.samplingRate().isPresent());
            assertEquals(0.75, config.samplingRate().get());
        }

        @Test
        @DisplayName("should deserialize config without rate")
        void shouldDeserializeConfigWithoutRate() throws JsonProcessingException {
            var json = "{}";

            var config = objectMapper.readValue(json, ServiceSamplingConfig.class);

            assertFalse(config.samplingRate().isPresent());
        }

        @Test
        @DisplayName("should deserialize config with null rate")
        void shouldDeserializeConfigWithNullRate() throws JsonProcessingException {
            var json = "{\"samplingRate\":null}";

            var config = objectMapper.readValue(json, ServiceSamplingConfig.class);

            assertFalse(config.samplingRate().isPresent());
        }
    }
}
