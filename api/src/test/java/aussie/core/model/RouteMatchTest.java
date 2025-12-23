package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.*;
import aussie.core.model.routing.*;
import aussie.core.model.service.*;

@DisplayName("RouteMatch")
class RouteMatchTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create RouteMatch with valid parameters")
        void shouldCreateWithValidParams() {
            var service = createTestService();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of("id", "123"));

            assertEquals(service, routeMatch.service());
            assertEquals(endpoint, routeMatch.endpointConfig());
            assertEquals("/api/users", routeMatch.targetPath());
            assertEquals("123", routeMatch.pathVariables().get("id"));
        }

        @Test
        @DisplayName("Should throw when service is null")
        void shouldThrowWhenServiceNull() {
            var endpoint = createTestEndpoint();

            assertThrows(IllegalArgumentException.class, () -> new RouteMatch(null, endpoint, "/api/users", Map.of()));
        }

        @Test
        @DisplayName("Should throw when endpoint is null")
        void shouldThrowWhenEndpointNull() {
            var service = createTestService();

            assertThrows(IllegalArgumentException.class, () -> new RouteMatch(service, null, "/api/users", Map.of()));
        }

        @Test
        @DisplayName("Should default targetPath to empty string when null")
        void shouldDefaultTargetPathWhenNull() {
            var service = createTestService();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, null, Map.of());

            assertEquals("", routeMatch.targetPath());
        }

        @Test
        @DisplayName("Should default pathVariables to empty map when null")
        void shouldDefaultPathVariablesWhenNull() {
            var service = createTestService();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", null);

            assertNotNull(routeMatch.pathVariables());
            assertTrue(routeMatch.pathVariables().isEmpty());
        }
    }

    @Nested
    @DisplayName("Target URI Building")
    class TargetUriTests {

        @Test
        @DisplayName("Should build target URI from base URL and path")
        void shouldBuildTargetUri() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users"), routeMatch.targetUri());
        }

        @Test
        @DisplayName("Should handle base URL with trailing slash")
        void shouldHandleTrailingSlash() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080/")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users"), routeMatch.targetUri());
        }

        @Test
        @DisplayName("Should handle path without leading slash")
        void shouldHandlePathWithoutLeadingSlash() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users"), routeMatch.targetUri());
        }

        @Test
        @DisplayName("Should handle root path")
        void shouldHandleRootPath() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/", Map.of());

            assertEquals(URI.create("http://backend:8080/"), routeMatch.targetUri());
        }

        @Test
        @DisplayName("Should append query string to target URI")
        void shouldAppendQueryString() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users?foo=bar"), routeMatch.targetUri("foo=bar"));
        }

        @Test
        @DisplayName("Should handle null query string")
        void shouldHandleNullQueryString() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users"), routeMatch.targetUri(null));
        }

        @Test
        @DisplayName("Should handle empty query string")
        void shouldHandleEmptyQueryString() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:8080")
                    .endpoints(List.of())
                    .build();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(URI.create("http://backend:8080/api/users"), routeMatch.targetUri(""));
        }
    }

    @Nested
    @DisplayName("RouteLookupResult Implementation")
    class RouteLookupResultTests {

        @Test
        @DisplayName("Should return endpoint as Optional")
        void shouldReturnEndpointAsOptional() {
            var service = createTestService();
            var endpoint = createTestEndpoint();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertTrue(routeMatch.endpoint().isPresent());
            assertEquals(endpoint, routeMatch.endpoint().get());
        }

        @Test
        @DisplayName("Should return endpoint visibility")
        void shouldReturnEndpointVisibility() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .endpoints(List.of())
                    .build();
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertEquals(EndpointVisibility.PUBLIC, routeMatch.visibility());
        }

        @Test
        @DisplayName("Should return endpoint authRequired")
        void shouldReturnEndpointAuthRequired() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultAuthRequired(false)
                    .endpoints(List.of())
                    .build();
            var endpoint =
                    new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), true);

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertTrue(routeMatch.authRequired());
        }

        @Test
        @DisplayName("Should return endpoint authRequired false")
        void shouldReturnEndpointAuthRequiredFalse() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultAuthRequired(true)
                    .endpoints(List.of())
                    .build();
            var endpoint = new EndpointConfig(
                    "/api/public", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);

            var routeMatch = new RouteMatch(service, endpoint, "/api/public", Map.of());

            assertFalse(routeMatch.authRequired());
        }

        @Test
        @DisplayName("Should return endpoint rate limit config when set")
        void shouldReturnEndpointRateLimitConfig() {
            var endpointRateLimit = EndpointRateLimitConfig.of(50, 30, 100);
            var serviceRateLimit = ServiceRateLimitConfig.of(100, 60, 200);
            var endpoint = new EndpointConfig(
                    "/api/users",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.of(endpointRateLimit));
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .rateLimitConfig(serviceRateLimit)
                    .endpoints(List.of())
                    .build();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertTrue(routeMatch.rateLimitConfig().isPresent());
            assertEquals(Optional.of(50L), routeMatch.rateLimitConfig().get().requestsPerWindow());
            assertEquals(Optional.of(30L), routeMatch.rateLimitConfig().get().windowSeconds());
        }

        @Test
        @DisplayName("Should fall back to service rate limit config when endpoint has none")
        void shouldFallBackToServiceRateLimitConfig() {
            var serviceRateLimit = ServiceRateLimitConfig.of(100, 60, 200);
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .rateLimitConfig(serviceRateLimit)
                    .endpoints(List.of())
                    .build();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertTrue(routeMatch.rateLimitConfig().isPresent());
            assertEquals(Optional.of(100L), routeMatch.rateLimitConfig().get().requestsPerWindow());
            assertEquals(Optional.of(60L), routeMatch.rateLimitConfig().get().windowSeconds());
        }

        @Test
        @DisplayName("Should return empty when no rate limit config is set")
        void shouldReturnEmptyWhenNoRateLimitConfig() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createTestService();

            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            assertFalse(routeMatch.rateLimitConfig().isPresent());
        }
    }

    private ServiceRegistration createTestService() {
        return ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .endpoints(List.of())
                .build();
    }

    private EndpointConfig createTestEndpoint() {
        return new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
    }
}
