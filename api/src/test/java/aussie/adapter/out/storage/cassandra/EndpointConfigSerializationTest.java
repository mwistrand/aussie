package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;

@DisplayName("EndpointConfig JSON Serialization Tests")
class EndpointConfigSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    @DisplayName("Should serialize and deserialize EndpointConfig with rateLimitConfig")
    void shouldSerializeAndDeserializeWithRateLimitConfig() throws Exception {
        var rateLimitConfig = new EndpointRateLimitConfig(Optional.of(100L), Optional.empty(), Optional.of(50L));

        var endpoint = new EndpointConfig(
                "/api/rate-limit-test",
                Set.of("GET"),
                EndpointVisibility.PUBLIC,
                Optional.empty(),
                false,
                EndpointType.HTTP,
                Optional.of(rateLimitConfig),
                Optional.empty());

        var endpoints = List.of(endpoint);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(endpoints);
        System.out.println("Serialized JSON: " + json);

        // Deserialize back
        List<EndpointConfig> deserialized = objectMapper.readValue(json, new TypeReference<List<EndpointConfig>>() {});

        assertEquals(1, deserialized.size());
        var deserializedEndpoint = deserialized.get(0);

        assertEquals("/api/rate-limit-test", deserializedEndpoint.path());
        assertTrue(deserializedEndpoint.rateLimitConfig().isPresent(), "rateLimitConfig should be present");

        var deserializedRateLimit = deserializedEndpoint.rateLimitConfig().get();
        assertTrue(deserializedRateLimit.requestsPerWindow().isPresent(), "requestsPerWindow should be present");
        assertEquals(100L, deserializedRateLimit.requestsPerWindow().get());
        assertTrue(deserializedRateLimit.burstCapacity().isPresent(), "burstCapacity should be present");
        assertEquals(50L, deserializedRateLimit.burstCapacity().get());
    }

    @Test
    @DisplayName("Should deserialize EndpointConfig from JSON string matching stored format")
    void shouldDeserializeFromStoredFormat() throws Exception {
        // This simulates what's stored in Cassandra
        String json =
                """
                [
                  {
                    "path": "/api/rate-limit-test",
                    "methods": ["GET"],
                    "visibility": "PUBLIC",
                    "authRequired": false,
                    "type": "HTTP",
                    "rateLimitConfig": {
                      "requestsPerWindow": 100,
                      "burstCapacity": 50
                    }
                  }
                ]
                """;

        List<EndpointConfig> deserialized = objectMapper.readValue(json, new TypeReference<List<EndpointConfig>>() {});

        assertEquals(1, deserialized.size());
        var endpoint = deserialized.get(0);

        assertEquals("/api/rate-limit-test", endpoint.path());
        assertTrue(endpoint.rateLimitConfig().isPresent(), "rateLimitConfig should be present");

        var rateLimit = endpoint.rateLimitConfig().get();
        assertTrue(rateLimit.requestsPerWindow().isPresent(), "requestsPerWindow should be present");
        assertEquals(100L, rateLimit.requestsPerWindow().get());
        assertTrue(rateLimit.burstCapacity().isPresent(), "burstCapacity should be present");
        assertEquals(50L, rateLimit.burstCapacity().get());
    }
}
