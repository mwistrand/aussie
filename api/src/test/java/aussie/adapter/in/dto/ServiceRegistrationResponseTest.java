package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;

@DisplayName("ServiceRegistrationResponse Tests")
class ServiceRegistrationResponseTest {

    @Test
    @DisplayName("fromModel should include rateLimitConfig when present")
    void shouldIncludeRateLimitConfigWhenPresent() {
        var rateLimitConfig = ServiceRateLimitConfig.of(100L, 60L, 50L);
        var registration = new ServiceRegistration(
                "test-service",
                "Test Service",
                URI.create("http://localhost:8080"),
                "/test",
                EndpointVisibility.PUBLIC,
                true,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(rateLimitConfig),
                Optional.empty(),
                1L);

        var response = ServiceRegistrationResponse.fromModel(registration);

        assertNotNull(response.rateLimitConfig());
        assertEquals(100L, response.rateLimitConfig().requestsPerWindow());
        assertEquals(60L, response.rateLimitConfig().windowSeconds());
        assertEquals(50L, response.rateLimitConfig().burstCapacity());
    }

    @Test
    @DisplayName("fromModel should have null rateLimitConfig when not present")
    void shouldHaveNullRateLimitConfigWhenNotPresent() {
        var registration = new ServiceRegistration(
                "test-service",
                "Test Service",
                URI.create("http://localhost:8080"),
                "/test",
                EndpointVisibility.PUBLIC,
                true,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1L);

        var response = ServiceRegistrationResponse.fromModel(registration);

        assertNull(response.rateLimitConfig());
    }

    @Test
    @DisplayName("fromModel should convert all fields correctly")
    void shouldConvertAllFieldsCorrectly() {
        var rateLimitConfig = ServiceRateLimitConfig.of(100L, 60L);
        var registration = new ServiceRegistration(
                "my-service",
                "My Service",
                URI.create("http://backend:9090"),
                "/api",
                EndpointVisibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(rateLimitConfig),
                Optional.empty(),
                5L);

        var response = ServiceRegistrationResponse.fromModel(registration);

        assertEquals("my-service", response.serviceId());
        assertEquals("My Service", response.displayName());
        assertEquals("http://backend:9090", response.baseUrl());
        assertEquals("/api", response.routePrefix());
        assertEquals("PRIVATE", response.defaultVisibility());
        assertEquals(false, response.defaultAuthRequired());
        assertEquals(5L, response.version());
        assertNotNull(response.rateLimitConfig());
        assertEquals(100L, response.rateLimitConfig().requestsPerWindow());
        assertEquals(60L, response.rateLimitConfig().windowSeconds());
        assertNull(response.rateLimitConfig().burstCapacity());
    }
}
