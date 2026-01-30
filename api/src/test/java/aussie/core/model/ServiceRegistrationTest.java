package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.*;
import aussie.core.model.ratelimit.*;
import aussie.core.model.routing.*;
import aussie.core.model.service.*;

@DisplayName("ServiceRegistration")
class ServiceRegistrationTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with minimal required fields")
        void shouldBuildWithMinimalFields() {
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            assertEquals("my-service", service.serviceId());
            assertEquals("my-service", service.displayName()); // defaults to serviceId
            assertEquals(URI.create("http://localhost:8080"), service.baseUrl());
            assertTrue(service.endpoints().isEmpty());
            assertFalse(service.accessConfig().isPresent());
        }

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var accessConfig =
                    new ServiceAccessConfig(Optional.of(List.of("10.0.0.0/8")), Optional.empty(), Optional.empty());

            var service = ServiceRegistration.builder("my-service")
                    .displayName("My Service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .accessConfig(accessConfig)
                    .build();

            assertEquals("my-service", service.serviceId());
            assertEquals("My Service", service.displayName());
            assertEquals(1, service.endpoints().size());
            assertTrue(service.accessConfig().isPresent());
        }

        @Test
        @DisplayName("Should throw when serviceId is null")
        void shouldThrowWhenServiceIdNull() {
            assertThrows(IllegalArgumentException.class, () -> ServiceRegistration.builder(null)
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build());
        }

        @Test
        @DisplayName("Should throw when baseUrl is null")
        void shouldThrowWhenBaseUrlNull() {
            // URI.create(null) throws NullPointerException
            assertThrows(NullPointerException.class, () -> ServiceRegistration.builder("my-service")
                    .baseUrl((String) null)
                    .endpoints(List.of())
                    .build());
        }

        @Test
        @DisplayName("Should accept URI for baseUrl")
        void shouldAcceptUriForBaseUrl() {
            var uri = URI.create("http://localhost:8080");
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl(uri)
                    .endpoints(List.of())
                    .build();

            assertEquals(uri, service.baseUrl());
        }
    }

    @Nested
    @DisplayName("Record")
    class RecordTests {

        @Test
        @DisplayName("Should create record directly")
        void shouldCreateRecordDirectly() {
            var service = new ServiceRegistration(
                    "my-service",
                    "My Service",
                    URI.create("http://localhost:8080"),
                    null, // routePrefix - defaults to /my-service
                    null, // defaultVisibility - defaults to PRIVATE
                    false, // defaultAuthRequired
                    null, // visibilityRules - defaults to empty list
                    List.of(),
                    Optional.empty(),
                    Optional.empty(), // corsConfig
                    Optional.empty(), // permissionPolicy
                    Optional.empty(), // rateLimitConfig
                    Optional.empty(), // samplingConfig
                    1L); // version

            assertNotNull(service);
            assertEquals("my-service", service.serviceId());
            assertEquals("/my-service", service.routePrefix());
            assertEquals(EndpointVisibility.PRIVATE, service.defaultVisibility());
        }

        @Test
        @DisplayName("Should have value-based equality")
        void shouldHaveValueBasedEquality() {
            var service1 = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            var service2 = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            assertEquals(service1, service2);
            assertEquals(service1.hashCode(), service2.hashCode());
        }
    }

    @Nested
    @DisplayName("Route Matching")
    class RouteMatchingTests {

        @Test
        @DisplayName("Should find route for exact path match")
        void shouldFindRouteForExactPath() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            var result = service.findRoute("/api/users", "GET");

            assertTrue(result.isPresent());
            assertEquals("/api/users", result.get().targetPath());
            assertEquals(service, result.get().service());
        }

        @Test
        @DisplayName("Should find route with path variables")
        void shouldFindRouteWithPathVariables() {
            var endpoint = new EndpointConfig(
                    "/api/users/{userId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            var result = service.findRoute("/api/users/123", "GET");

            assertTrue(result.isPresent());
            assertEquals("123", result.get().pathVariables().get("userId"));
        }

        @Test
        @DisplayName("Should apply path rewrite")
        void shouldApplyPathRewrite() {
            var endpoint = new EndpointConfig(
                    "/api/v1/users/{userId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.of("/users/{userId}"));
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            var result = service.findRoute("/api/v1/users/456", "GET");

            assertTrue(result.isPresent());
            assertEquals("/users/456", result.get().targetPath());
        }

        @Test
        @DisplayName("Should not match wrong HTTP method")
        void shouldNotMatchWrongMethod() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            var result = service.findRoute("/api/users", "POST");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should match wildcard method")
        void shouldMatchWildcardMethod() {
            var endpoint = new EndpointConfig("/api/data", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            assertTrue(service.findRoute("/api/data", "GET").isPresent());
            assertTrue(service.findRoute("/api/data", "POST").isPresent());
            assertTrue(service.findRoute("/api/data", "DELETE").isPresent());
        }

        @Test
        @DisplayName("Should return empty for unmatched path")
        void shouldReturnEmptyForUnmatchedPath() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();

            var result = service.findRoute("/api/products", "GET");

            assertFalse(result.isPresent());
        }
    }
}
