package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import io.smallrye.mutiny.Uni;
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

    @Nested
    @DisplayName("Request Coalescing")
    class RequestCoalescing {

        @Test
        @DisplayName("Concurrent findRouteAsync calls should coalesce into a single refresh")
        void concurrentCallsShouldCoalesceIntoSingleRefresh() throws Exception {
            final var findAllCount = new AtomicInteger(0);
            var countingRepo = new CountingRepository(sharedRepository, findAllCount);

            var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
            var authService = new ServiceAuthorizationService(new DefaultPermissionPolicy());
            var shortTtlConfig = shortTtlCacheConfig(Duration.ofMillis(50));

            var registry = new ServiceRegistry(
                    countingRepo, NoOpConfigurationCache.INSTANCE, validator, authService, shortTtlConfig);
            registry.initialize().await().atMost(TIMEOUT);

            // Register a service directly in the shared repo
            var endpoint = new EndpointConfig("/api/data", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("data-service")
                    .baseUrl("http://localhost:8090")
                    .endpoints(List.of(endpoint))
                    .build();
            sharedRepository.save(service).await().atMost(TIMEOUT);

            // Wait for TTL to expire
            Thread.sleep(100);

            // Reset counter after initialization
            findAllCount.set(0);

            // Launch concurrent findRouteAsync calls from multiple threads
            final int threadCount = 5;
            final var startLatch = new CountDownLatch(1);
            final var doneLatch = new CountDownLatch(threadCount);
            final var errors = new ArrayList<Throwable>();

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                            try {
                                startLatch.await();
                                registry.findRouteAsync("/api/data", "GET")
                                        .await()
                                        .atMost(TIMEOUT);
                            } catch (Throwable e) {
                                synchronized (errors) {
                                    errors.add(e);
                                }
                            } finally {
                                doneLatch.countDown();
                            }
                        })
                        .start();
            }

            // Release all threads at once
            startLatch.countDown();
            doneLatch.await();

            assertTrue(errors.isEmpty(), "Unexpected errors: " + errors);

            // findAll should have been called far fewer times than the thread count
            assertTrue(
                    findAllCount.get() <= 2,
                    "Expected at most 2 findAll calls from " + threadCount + " concurrent requests, got "
                            + findAllCount.get());
        }

        @Test
        @DisplayName("Failed refresh should clear in-flight reference allowing retry")
        void failedRefreshShouldAllowRetry() throws InterruptedException {
            final var callCount = new AtomicInteger(0);

            // Repository that fails on the first findAll, then succeeds
            var failOnceRepo = new FailOnceRepository(sharedRepository, callCount);

            var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
            var authService = new ServiceAuthorizationService(new DefaultPermissionPolicy());
            var shortTtlConfig = shortTtlCacheConfig(Duration.ofMillis(50));

            var registry = new ServiceRegistry(
                    failOnceRepo, NoOpConfigurationCache.INSTANCE, validator, authService, shortTtlConfig);

            // Register a service in the shared repo
            var endpoint = new EndpointConfig("/api/retry", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("retry-service")
                    .baseUrl("http://localhost:8091")
                    .endpoints(List.of(endpoint))
                    .build();
            sharedRepository.save(service).await().atMost(TIMEOUT);

            // First call: findAll will fail, so route won't be found
            try {
                registry.findRouteAsync("/api/retry", "GET").await().atMost(TIMEOUT);
            } catch (Exception ignored) {
                // Expected to fail
            }

            // Wait for TTL to expire again
            Thread.sleep(100);

            // Second call: findAll should succeed now and find the route
            var result = registry.findRouteAsync("/api/retry", "GET").await().atMost(TIMEOUT);
            assertTrue(result.isPresent(), "Route should be found after retry succeeds");
        }
    }

    private LocalCacheConfig shortTtlCacheConfig(Duration ttl) {
        return new LocalCacheConfig() {
            @Override
            public Duration serviceRoutesTtl() {
                return ttl;
            }

            @Override
            public Duration rateLimitConfigTtl() {
                return ttl;
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
                return 0.0;
            }
        };
    }

    /**
     * Repository wrapper that counts findAll invocations.
     */
    private static class CountingRepository implements ServiceRegistrationRepository {
        private final ServiceRegistrationRepository delegate;
        private final AtomicInteger findAllCount;

        CountingRepository(ServiceRegistrationRepository delegate, AtomicInteger findAllCount) {
            this.delegate = delegate;
            this.findAllCount = findAllCount;
        }

        @Override
        public Uni<List<ServiceRegistration>> findAll() {
            // Count actual executions (subscriptions), not Uni creation
            return Uni.createFrom().emitter(em -> {
                findAllCount.incrementAndGet();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delegate.findAll().subscribe().with(em::complete, em::fail);
            });
        }

        @Override
        public Uni<Void> save(ServiceRegistration registration) {
            return delegate.save(registration);
        }

        @Override
        public Uni<Optional<ServiceRegistration>> findById(String serviceId) {
            return delegate.findById(serviceId);
        }

        @Override
        public Uni<Boolean> delete(String serviceId) {
            return delegate.delete(serviceId);
        }

        @Override
        public Uni<Boolean> exists(String serviceId) {
            return delegate.exists(serviceId);
        }

        @Override
        public Uni<Long> count() {
            return delegate.count();
        }
    }

    /**
     * Repository that fails on the first findAll call, then delegates normally.
     */
    private static class FailOnceRepository implements ServiceRegistrationRepository {
        private final ServiceRegistrationRepository delegate;
        private final AtomicInteger callCount;

        FailOnceRepository(ServiceRegistrationRepository delegate, AtomicInteger callCount) {
            this.delegate = delegate;
            this.callCount = callCount;
        }

        @Override
        public Uni<List<ServiceRegistration>> findAll() {
            if (callCount.incrementAndGet() == 1) {
                return Uni.createFrom().failure(new RuntimeException("Simulated repository failure"));
            }
            return delegate.findAll();
        }

        @Override
        public Uni<Void> save(ServiceRegistration registration) {
            return delegate.save(registration);
        }

        @Override
        public Uni<Optional<ServiceRegistration>> findById(String serviceId) {
            return delegate.findById(serviceId);
        }

        @Override
        public Uni<Boolean> delete(String serviceId) {
            return delegate.delete(serviceId);
        }

        @Override
        public Uni<Boolean> exists(String serviceId) {
            return delegate.exists(serviceId);
        }

        @Override
        public Uni<Long> count() {
            return delegate.count();
        }
    }
}
