package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.NoOpConfigurationCache;
import aussie.adapter.out.storage.memory.InMemoryServiceRegistrationRepository;
import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.GatewaySecurityConfig;
import aussie.core.model.RegistrationResult;
import aussie.core.model.ServiceRegistration;

@DisplayName("ServiceRegistry")
class ServiceRegistryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private ServiceRegistry registry;

    // Permissive security config for testing
    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;

    @BeforeEach
    void setUp() {
        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
        registry = new ServiceRegistry(
                new InMemoryServiceRegistrationRepository(), NoOpConfigurationCache.INSTANCE, validator);
    }

    @Nested
    @DisplayName("Service Registration")
    class RegistrationTests {

        @Test
        @DisplayName("Should register a service")
        void shouldRegisterService() {
            var service = createService("test-service", "http://localhost:8080");
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
            var retrieved = registry.getService("test-service").await().atMost(TIMEOUT);
            assertTrue(retrieved.isPresent());
            assertEquals("test-service", retrieved.get().serviceId());
        }

        @Test
        @DisplayName("Should replace existing service on re-registration")
        void shouldReplaceExistingService() {
            var service1 = createService("test-service", "http://localhost:8080");
            var service2 = createService("test-service", "http://localhost:9090");

            registry.register(service1).await().atMost(TIMEOUT);
            registry.register(service2).await().atMost(TIMEOUT);

            var result = registry.getService("test-service").await().atMost(TIMEOUT);
            assertTrue(result.isPresent());
            assertEquals(URI.create("http://localhost:9090"), result.get().baseUrl());
        }

        @Test
        @DisplayName("Should unregister a service")
        void shouldUnregisterService() {
            var service = createService("test-service", "http://localhost:8080");
            registry.register(service).await().atMost(TIMEOUT);
            registry.unregister("test-service").await().atMost(TIMEOUT);

            var result = registry.getService("test-service").await().atMost(TIMEOUT);
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should handle unregistering non-existent service")
        void shouldHandleUnregisteringNonExistent() {
            registry.unregister("non-existent").await().atMost(TIMEOUT);
            // Should not throw
            assertFalse(
                    registry.getService("non-existent").await().atMost(TIMEOUT).isPresent());
        }

        @Test
        @DisplayName("Should list all registered services")
        void shouldListAllServices() {
            registry.register(createService("service-1", "http://localhost:8081"))
                    .await()
                    .atMost(TIMEOUT);
            registry.register(createService("service-2", "http://localhost:8082"))
                    .await()
                    .atMost(TIMEOUT);
            registry.register(createService("service-3", "http://localhost:8083"))
                    .await()
                    .atMost(TIMEOUT);

            var services = registry.getAllServices().await().atMost(TIMEOUT);
            assertEquals(3, services.size());
        }

        @Test
        @DisplayName("Should return empty list when no services registered")
        void shouldReturnEmptyListWhenNoServices() {
            var services = registry.getAllServices().await().atMost(TIMEOUT);
            assertTrue(services.isEmpty());
        }
    }

    @Nested
    @DisplayName("Route Matching")
    class RouteMatchingTests {

        @Test
        @DisplayName("Should match exact path")
        void shouldMatchExactPath() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/users", "GET");
            assertTrue(result.isPresent());
            assertEquals("/api/users", result.get().targetPath());
        }

        @Test
        @DisplayName("Should match path with path variables")
        void shouldMatchPathWithVariables() {
            var endpoint = new EndpointConfig(
                    "/api/users/{userId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/users/123", "GET");
            assertTrue(result.isPresent());
            assertEquals("123", result.get().pathVariables().get("userId"));
        }

        @Test
        @DisplayName("Should match path with multiple path variables")
        void shouldMatchPathWithMultipleVariables() {
            var endpoint = new EndpointConfig(
                    "/api/users/{userId}/posts/{postId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/users/123/posts/456", "GET");
            assertTrue(result.isPresent());
            assertEquals("123", result.get().pathVariables().get("userId"));
            assertEquals("456", result.get().pathVariables().get("postId"));
        }

        @Test
        @DisplayName("Should match wildcard path with **")
        void shouldMatchWildcardPath() {
            var endpoint = new EndpointConfig("/api/**", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("api-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            assertTrue(registry.findRoute("/api/users", "GET").isPresent());
            assertTrue(registry.findRoute("/api/users/123/posts", "GET").isPresent());
            assertTrue(registry.findRoute("/api/deeply/nested/path/here", "GET").isPresent());
        }

        @Test
        @DisplayName("Should match single segment wildcard with *")
        void shouldMatchSingleSegmentWildcard() {
            var endpoint =
                    new EndpointConfig("/api/*/info", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("api-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            assertTrue(registry.findRoute("/api/users/info", "GET").isPresent());
            assertTrue(registry.findRoute("/api/products/info", "GET").isPresent());
            assertFalse(registry.findRoute("/api/users/extra/info", "GET").isPresent());
        }

        @Test
        @DisplayName("Should not match wrong HTTP method")
        void shouldNotMatchWrongMethod() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/users", "POST");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should match any method with wildcard")
        void shouldMatchAnyMethodWithWildcard() {
            var endpoint = new EndpointConfig("/api/data", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("data-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            assertTrue(registry.findRoute("/api/data", "GET").isPresent());
            assertTrue(registry.findRoute("/api/data", "POST").isPresent());
            assertTrue(registry.findRoute("/api/data", "DELETE").isPresent());
        }

        @Test
        @DisplayName("Should match case-insensitive HTTP methods")
        void shouldMatchCaseInsensitiveMethods() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            assertTrue(registry.findRoute("/api/users", "get").isPresent());
            assertTrue(registry.findRoute("/api/users", "Get").isPresent());
            assertTrue(registry.findRoute("/api/users", "GET").isPresent());
        }

        @Test
        @DisplayName("Should return empty for unmatched path")
        void shouldReturnEmptyForUnmatchedPath() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/products", "GET");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should normalize paths without leading slash")
        void shouldNormalizePathsWithoutLeadingSlash() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("api/users", "GET");
            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Should handle null path")
        void shouldHandleNullPath() {
            var endpoint = new EndpointConfig("/", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("root-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute(null, "GET");
            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Should handle empty path")
        void shouldHandleEmptyPath() {
            var endpoint = new EndpointConfig("/", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("root-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("", "GET");
            assertTrue(result.isPresent());
        }
    }

    @Nested
    @DisplayName("Path Rewriting")
    class PathRewritingTests {

        @Test
        @DisplayName("Should apply path rewrite")
        void shouldApplyPathRewrite() {
            var endpoint = new EndpointConfig(
                    "/api/v1/users/{userId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.of("/users/{userId}"));
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/v1/users/123", "GET");
            assertTrue(result.isPresent());
            assertEquals("/users/123", result.get().targetPath());
        }

        @Test
        @DisplayName("Should apply path rewrite with multiple variables")
        void shouldApplyPathRewriteWithMultipleVariables() {
            var endpoint = new EndpointConfig(
                    "/api/v2/{org}/users/{userId}",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.of("/orgs/{org}/members/{userId}"));
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            var result = registry.findRoute("/api/v2/acme/users/456", "GET");
            assertTrue(result.isPresent());
            assertEquals("/orgs/acme/members/456", result.get().targetPath());
        }
    }

    @Nested
    @DisplayName("Route Cleanup")
    class RouteCleanupTests {

        @Test
        @DisplayName("Should remove routes when service is unregistered")
        void shouldRemoveRoutesOnUnregister() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            assertTrue(registry.findRoute("/api/users", "GET").isPresent());

            registry.unregister("user-service").await().atMost(TIMEOUT);

            assertFalse(registry.findRoute("/api/users", "GET").isPresent());
        }
    }

    private ServiceRegistration createService(String serviceId, String baseUrl) {
        return ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of())
                .build();
    }
}
