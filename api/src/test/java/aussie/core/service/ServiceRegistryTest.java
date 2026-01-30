package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.NoOpConfigurationCache;
import aussie.adapter.out.storage.memory.InMemoryServiceRegistrationRepository;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.auth.OperationPermission;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.*;
import aussie.core.service.routing.*;

@DisplayName("ServiceRegistry")
class ServiceRegistryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private ServiceRegistry registry;
    private ServiceAuthorizationService authService;

    // Permissive security config for testing
    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;

    // Test cache config with short TTL for fast tests
    private static final LocalCacheConfig TEST_CACHE_CONFIG = new LocalCacheConfig() {
        @Override
        public Duration serviceRoutesTtl() {
            return Duration.ofMinutes(5);
        }

        @Override
        public Duration rateLimitConfigTtl() {
            return Duration.ofMinutes(5);
        }

        @Override
        public Duration samplingConfigTtl() {
            return Duration.ofMinutes(5);
        }

        @Override
        public long maxEntries() {
            return 1000;
        }

        @Override
        public double jitterFactor() {
            return 0.0; // No jitter in tests for predictable behavior
        }
    };

    @BeforeEach
    void setUp() {
        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
        var defaultPolicy = new DefaultPermissionPolicy();
        authService = new ServiceAuthorizationService(defaultPolicy);
        registry = new ServiceRegistry(
                new InMemoryServiceRegistrationRepository(),
                NoOpConfigurationCache.INSTANCE,
                validator,
                authService,
                TEST_CACHE_CONFIG);
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
        @DisplayName("Should replace existing service on re-registration with correct version")
        void shouldReplaceExistingService() {
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", 2);

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
    @DisplayName("Version Validation")
    class VersionValidationTests {

        @Test
        @DisplayName("Should succeed for new service with version 1")
        void shouldSucceedForNewServiceWithVersion1() {
            var service = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should fail for new service with version 0")
        void shouldFailForNewServiceWithVersion0() {
            var service = createServiceWithVersion("test-service", "http://localhost:8080", 0);
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertTrue(failure.reason().contains("must have version 1"));
            assertEquals(409, failure.statusCode());
        }

        @Test
        @DisplayName("Should fail for new service with version 2")
        void shouldFailForNewServiceWithVersion2() {
            var service = createServiceWithVersion("test-service", "http://localhost:8080", 2);
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertTrue(failure.reason().contains("must have version 1"));
            assertEquals(409, failure.statusCode());
        }

        @Test
        @DisplayName("Should succeed for update with version current+1")
        void shouldSucceedForUpdateWithCorrectVersion() {
            // Register initial service with version 1
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            registry.register(service1).await().atMost(TIMEOUT);

            // Update with version 2 (current + 1)
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", 2);
            var result = registry.register(service2).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
            var retrieved = registry.getService("test-service").await().atMost(TIMEOUT);
            assertTrue(retrieved.isPresent());
            assertEquals(URI.create("http://localhost:9090"), retrieved.get().baseUrl());
            assertEquals(2, retrieved.get().version());
        }

        @Test
        @DisplayName("Should fail for update with same version")
        void shouldFailForUpdateWithSameVersion() {
            // Register initial service with version 1
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            registry.register(service1).await().atMost(TIMEOUT);

            // Try to update with version 1 (same as current)
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", 1);
            var result = registry.register(service2).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertTrue(failure.reason().contains("Version conflict"));
            assertTrue(failure.reason().contains("expected version 2"));
            assertEquals(409, failure.statusCode());
        }

        @Test
        @DisplayName("Should fail for update with version too high")
        void shouldFailForUpdateWithVersionTooHigh() {
            // Register initial service with version 1
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            registry.register(service1).await().atMost(TIMEOUT);

            // Try to update with version 3 (skipping version 2)
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", 3);
            var result = registry.register(service2).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertTrue(failure.reason().contains("Version conflict"));
            assertTrue(failure.reason().contains("expected version 2"));
            assertEquals(409, failure.statusCode());
        }

        @Test
        @DisplayName("Should fail for update with version 0")
        void shouldFailForUpdateWithVersion0() {
            // Register initial service with version 1
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            registry.register(service1).await().atMost(TIMEOUT);

            // Try to update with version 0
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", 0);
            var result = registry.register(service2).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertTrue(failure.reason().contains("Version conflict"));
            assertEquals(409, failure.statusCode());
        }

        @Test
        @DisplayName("Should fail for update with negative version")
        void shouldFailForUpdateWithNegativeVersion() {
            // Register initial service with version 1
            var service1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            registry.register(service1).await().atMost(TIMEOUT);

            // Try to update with version -5
            var service2 = createServiceWithVersion("test-service", "http://localhost:9090", -5);
            var result = registry.register(service2).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            assertEquals(409, ((RegistrationResult.Failure) result).statusCode());
        }

        @Test
        @DisplayName("Should allow sequential version updates")
        void shouldAllowSequentialVersionUpdates() {
            // Version 1
            var v1 = createServiceWithVersion("test-service", "http://localhost:8080", 1);
            assertInstanceOf(
                    RegistrationResult.Success.class,
                    registry.register(v1).await().atMost(TIMEOUT));

            // Version 2
            var v2 = createServiceWithVersion("test-service", "http://localhost:8080", 2);
            assertInstanceOf(
                    RegistrationResult.Success.class,
                    registry.register(v2).await().atMost(TIMEOUT));

            // Version 3
            var v3 = createServiceWithVersion("test-service", "http://localhost:8080", 3);
            assertInstanceOf(
                    RegistrationResult.Success.class,
                    registry.register(v3).await().atMost(TIMEOUT));

            var retrieved = registry.getService("test-service").await().atMost(TIMEOUT);
            assertTrue(retrieved.isPresent());
            assertEquals(3, retrieved.get().version());
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
            assertInstanceOf(RouteMatch.class, result.get());
            assertEquals("/api/users", ((RouteMatch) result.get()).targetPath());
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
            assertInstanceOf(RouteMatch.class, result.get());
            assertEquals("123", ((RouteMatch) result.get()).pathVariables().get("userId"));
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
            assertInstanceOf(RouteMatch.class, result.get());
            var routeMatch = (RouteMatch) result.get();
            assertEquals("123", routeMatch.pathVariables().get("userId"));
            assertEquals("456", routeMatch.pathVariables().get("postId"));
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
            var extra = registry.findRoute("/api/users/extra/info", "GET");
            assertTrue(extra.isPresent());
            assertInstanceOf(ServiceOnlyMatch.class, extra.get());
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
            assertTrue(result.isPresent());
            assertInstanceOf(ServiceOnlyMatch.class, result.get());
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
        @DisplayName("Should return ServiceOnlyMatch when service matches but no endpoint")
        void shouldReturnServiceOnlyMatchWhenNoEndpointMatches() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            registry.register(service).await().atMost(TIMEOUT);

            // This path does not match the endpoint, but the registry iterates services
            // and should return a ServiceOnlyMatch wrapping the service
            var result = registry.findRoute("/api/products", "GET");
            assertTrue(result.isPresent());
            assertInstanceOf(ServiceOnlyMatch.class, result.get());
            assertEquals("user-service", result.get().service().serviceId());
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
            assertInstanceOf(RouteMatch.class, result.get());
            assertEquals("/users/123", ((RouteMatch) result.get()).targetPath());
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
            assertInstanceOf(RouteMatch.class, result.get());
            assertEquals("/orgs/acme/members/456", ((RouteMatch) result.get()).targetPath());
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

    @Nested
    @DisplayName("Authorization")
    class AuthorizationTests {

        private static final Set<String> ADMIN_PERMISSIONS = Set.of("*");
        private static final Set<String> LEAD_PERMISSIONS = Set.of("test-service.lead");
        private static final Set<String> READONLY_PERMISSIONS = Set.of("test-service.readonly");
        private static final Set<String> NO_PERMISSIONS = Set.of();

        @Test
        @DisplayName("Should allow admin to create service")
        void shouldAllowAdminToCreateService() {
            var service = createService("test-service", "http://localhost:8080");
            var result = registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should deny unauthorized user from creating service")
        void shouldDenyUnauthorizedUserFromCreatingService() {
            var service = createService("test-service", "http://localhost:8080");
            var result = registry.register(service, NO_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertEquals(403, failure.statusCode());
            assertTrue(failure.reason().contains("Not authorized to create"));
        }

        @Test
        @DisplayName("Should allow update when permission matches service policy")
        void shouldAllowUpdateWhenPermissionMatchesPolicy() {
            // Create service with permission policy
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.update", new OperationPermission(Set.of("test-service.lead"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Update with lead permissions
            var updatedService = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .version(2)
                    .build();
            var result =
                    registry.register(updatedService, LEAD_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should deny update when permission does not match service policy")
        void shouldDenyUpdateWhenPermissionDoesNotMatchPolicy() {
            // Create service with permission policy
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.update", new OperationPermission(Set.of("test-service.lead"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Try to update with readonly permissions
            var updatedService = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .version(2)
                    .build();
            var result = registry.register(updatedService, READONLY_PERMISSIONS)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertEquals(403, failure.statusCode());
            assertTrue(failure.reason().contains("Not authorized to update"));
        }

        @Test
        @DisplayName("Should allow permission policy change with same policy")
        void shouldAllowSamePermissionPolicy() {
            // Create service with permission policy
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.update", new OperationPermission(Set.of("test-service.lead"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Update with same policy (should not require permissions.write)
            var updatedService = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .permissionPolicy(policy) // Same policy
                    .version(2)
                    .build();
            var result =
                    registry.register(updatedService, LEAD_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should require permissions.write when policy changes")
        void shouldRequirePermissionsWriteWhenPolicyChanges() {
            // Create service with permission policy
            var initialPolicy = new ServicePermissionPolicy(
                    Map.of("service.config.update", new OperationPermission(Set.of("test-service.lead"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(initialPolicy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Try to update with different policy (should require permissions.write)
            var newPolicy = new ServicePermissionPolicy(Map.of(
                    "service.config.update",
                    new OperationPermission(Set.of("test-service.lead", "test-service.admin"))));
            var updatedService = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .permissionPolicy(newPolicy) // Different policy
                    .version(2)
                    .build();
            var result =
                    registry.register(updatedService, LEAD_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertEquals(403, failure.statusCode());
            assertTrue(failure.reason().contains("Not authorized to update permissions"));
        }

        @Test
        @DisplayName("Should allow admin to change permission policy")
        void shouldAllowAdminToChangePermissionPolicy() {
            // Create service with permission policy
            var initialPolicy = new ServicePermissionPolicy(
                    Map.of("service.config.update", new OperationPermission(Set.of("test-service.lead"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(initialPolicy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Update with different policy using admin permissions
            var newPolicy = new ServicePermissionPolicy(Map.of(
                    "service.config.update",
                    new OperationPermission(Set.of("test-service.lead", "test-service.admin"))));
            var updatedService = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .permissionPolicy(newPolicy)
                    .version(2)
                    .build();
            var result =
                    registry.register(updatedService, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should deny unauthorized delete")
        void shouldDenyUnauthorizedDelete() {
            // Create service
            var service = createService("test-service", "http://localhost:8080");
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Try to delete with readonly permissions
            var result = registry.unregisterAuthorized("test-service", READONLY_PERMISSIONS)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertEquals(403, failure.statusCode());
        }

        @Test
        @DisplayName("Should allow admin to delete")
        void shouldAllowAdminToDelete() {
            // Create service
            var service = createService("test-service", "http://localhost:8080");
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Delete with admin permissions
            var result = registry.unregisterAuthorized("test-service", ADMIN_PERMISSIONS)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
        }

        @Test
        @DisplayName("Should deny unauthorized read")
        void shouldDenyUnauthorizedRead() {
            // Create service with permission policy requiring readonly permission
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("test-service.readonly"))));
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .permissionPolicy(policy)
                    .build();
            registry.register(service, ADMIN_PERMISSIONS).await().atMost(TIMEOUT);

            // Try to read with different permissions
            var result = registry.getServiceAuthorized("test-service", Set.of("other-service.readonly"))
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            var failure = (RegistrationResult.Failure) result;
            assertEquals(403, failure.statusCode());
        }
    }

    private ServiceRegistration createService(String serviceId, String baseUrl) {
        return ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of())
                .build();
    }

    private ServiceRegistration createServiceWithVersion(String serviceId, String baseUrl, long version) {
        return ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of())
                .version(version)
                .build();
    }
}
