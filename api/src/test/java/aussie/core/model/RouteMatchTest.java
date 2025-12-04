package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(endpoint, routeMatch.endpoint());
            assertEquals("/api/users", routeMatch.targetPath());
            assertEquals("123", routeMatch.pathVariables().get("id"));
        }

        @Test
        @DisplayName("Should throw when service is null")
        void shouldThrowWhenServiceNull() {
            var endpoint = createTestEndpoint();

            assertThrows(IllegalArgumentException.class, () ->
                new RouteMatch(null, endpoint, "/api/users", Map.of()));
        }

        @Test
        @DisplayName("Should throw when endpoint is null")
        void shouldThrowWhenEndpointNull() {
            var service = createTestService();

            assertThrows(IllegalArgumentException.class, () ->
                new RouteMatch(service, null, "/api/users", Map.of()));
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
