package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import aussie.core.cache.LocalCacheConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.service.auth.*;
import aussie.core.service.routing.*;

/**
 * Test for ServiceRegistry multi-instance cache behavior.
 *
 * <p>Verifies that the TTL-based cache refresh mechanism works correctly,
 * simulating multi-instance deployment scenarios where services are registered
 * on one instance and must become visible on another.
 */
@DisplayName("ServiceRegistry Multi-Instance Cache")
class ServiceRegistryMultiInstanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // Permissive security config for testing
    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;

    private ServiceRegistrationRepository sharedRepository;
    private ServiceRegistry instanceA;
    private ServiceRegistry instanceB;

    @BeforeEach
    void setUp() {
        // Shared repository simulates persistent storage
        sharedRepository = new InMemoryServiceRegistrationRepository();

        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
        var defaultPolicy = new DefaultPermissionPolicy();
        var authService = new ServiceAuthorizationService(defaultPolicy);

        // Short TTL for testing multi-instance refresh
        var shortTtlConfig = new LocalCacheConfig() {
            @Override
            public Duration serviceRoutesTtl() {
                return Duration.ofMillis(100);
            }

            @Override
            public Duration rateLimitConfigTtl() {
                return Duration.ofMillis(100);
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

        // Create two instances sharing the same repository
        instanceA = new ServiceRegistry(
                sharedRepository, NoOpConfigurationCache.INSTANCE, validator, authService, shortTtlConfig);

        instanceB = new ServiceRegistry(
                sharedRepository, NoOpConfigurationCache.INSTANCE, validator, authService, shortTtlConfig);
    }

    @Nested
    @DisplayName("Cross-Instance Visibility")
    class CrossInstanceVisibility {

        @Test
        @DisplayName("Service registered on Instance A should be visible on Instance B after cache TTL")
        void serviceRegisteredOnInstanceAShouldBeVisibleOnInstanceBAfterTtl() throws InterruptedException {
            // Initialize both instances
            instanceA.initialize().await().atMost(TIMEOUT);
            instanceB.initialize().await().atMost(TIMEOUT);

            // Register a service on Instance A
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("user-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .build();
            instanceA.register(service).await().atMost(TIMEOUT);

            // Instance A should see the service immediately
            assertTrue(instanceA.findRoute("/api/users", "GET").isPresent());

            // Instance B cache is stale, should not see it yet (sync method doesn't refresh)
            assertFalse(instanceB.findRoute("/api/users", "GET").isPresent());

            // Wait for TTL to expire
            Thread.sleep(150);

            // Use async method which refreshes cache - Instance B should now see the service
            var result = instanceB.findRouteAsync("/api/users", "GET").await().atMost(TIMEOUT);
            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Service unregistered on Instance A should disappear from Instance B after cache TTL")
        void serviceUnregisteredOnInstanceAShouldDisappearFromInstanceBAfterTtl() throws InterruptedException {
            // Register service and initialize both instances
            var endpoint =
                    new EndpointConfig("/api/products", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("product-service")
                    .baseUrl("http://localhost:8081")
                    .endpoints(List.of(endpoint))
                    .build();
            sharedRepository.save(service).await().atMost(TIMEOUT);

            instanceA.initialize().await().atMost(TIMEOUT);
            instanceB.initialize().await().atMost(TIMEOUT);

            // Both instances should see the service
            assertTrue(instanceA.findRoute("/api/products", "GET").isPresent());
            assertTrue(instanceB.findRoute("/api/products", "GET").isPresent());

            // Unregister on Instance A
            instanceA.unregister("product-service").await().atMost(TIMEOUT);

            // Instance A should not see it anymore (local cache updated)
            assertFalse(instanceA.findRoute("/api/products", "GET").isPresent());

            // Instance B still sees it (stale cache)
            assertTrue(instanceB.findRoute("/api/products", "GET").isPresent());

            // Wait for TTL to expire
            Thread.sleep(150);

            // Use async method - Instance B should now see the service is gone
            var result =
                    instanceB.findRouteAsync("/api/products", "GET").await().atMost(TIMEOUT);
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findRouteAsync should refresh cache when TTL expired")
        void findRouteAsyncShouldRefreshCacheWhenTtlExpired() throws InterruptedException {
            // Initialize instance
            instanceA.initialize().await().atMost(TIMEOUT);

            // Initially no routes
            assertFalse(instanceA.findRoute("/api/orders", "GET").isPresent());

            // Register service directly to repository (simulating another instance)
            var endpoint =
                    new EndpointConfig("/api/orders", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("order-service")
                    .baseUrl("http://localhost:8082")
                    .endpoints(List.of(endpoint))
                    .build();
            sharedRepository.save(service).await().atMost(TIMEOUT);

            // Sync method still returns empty (stale cache)
            assertFalse(instanceA.findRoute("/api/orders", "GET").isPresent());

            // Wait for TTL to expire
            Thread.sleep(150);

            // Async method should refresh and find the route
            var result = instanceA.findRouteAsync("/api/orders", "GET").await().atMost(TIMEOUT);
            assertTrue(result.isPresent());
        }
    }

    @Nested
    @DisplayName("Cache Freshness")
    class CacheFreshness {

        @Test
        @DisplayName("findRouteAsync should not refresh cache when still fresh")
        void findRouteAsyncShouldNotRefreshCacheWhenStillFresh() throws InterruptedException {
            // Initialize instance
            instanceA.initialize().await().atMost(TIMEOUT);

            // Register service directly to repository
            var endpoint = new EndpointConfig("/api/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("item-service")
                    .baseUrl("http://localhost:8083")
                    .endpoints(List.of(endpoint))
                    .build();
            sharedRepository.save(service).await().atMost(TIMEOUT);

            // Short wait - cache should still be fresh
            Thread.sleep(20);

            // Async method should still return empty (cache is fresh, no refresh)
            var result = instanceA.findRouteAsync("/api/items", "GET").await().atMost(TIMEOUT);
            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("Local Consistency")
    class LocalConsistency {

        @Test
        @DisplayName("Local registration should be immediately visible")
        void localRegistrationShouldBeImmediatelyVisible() {
            instanceA.initialize().await().atMost(TIMEOUT);

            var endpoint = new EndpointConfig("/api/local", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("local-service")
                    .baseUrl("http://localhost:8084")
                    .endpoints(List.of(endpoint))
                    .build();

            // Register locally
            instanceA.register(service).await().atMost(TIMEOUT);

            // Should be immediately visible without waiting for TTL
            assertTrue(instanceA.findRoute("/api/local", "GET").isPresent());
        }

        @Test
        @DisplayName("Local unregistration should be immediately effective")
        void localUnregistrationShouldBeImmediatelyEffective() {
            instanceA.initialize().await().atMost(TIMEOUT);

            var endpoint = new EndpointConfig("/api/temp", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("temp-service")
                    .baseUrl("http://localhost:8085")
                    .endpoints(List.of(endpoint))
                    .build();

            instanceA.register(service).await().atMost(TIMEOUT);
            assertTrue(instanceA.findRoute("/api/temp", "GET").isPresent());

            // Unregister locally
            instanceA.unregister("temp-service").await().atMost(TIMEOUT);

            // Should be immediately not visible without waiting for TTL
            assertFalse(instanceA.findRoute("/api/temp", "GET").isPresent());
        }
    }
}
