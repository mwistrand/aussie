package aussie.core.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.NoOpConfigurationCache;
import aussie.adapter.out.storage.memory.InMemoryServiceConfigEventPublisher;
import aussie.adapter.out.storage.memory.InMemoryServiceRegistrationRepository;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceConfigEvent;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.DefaultPermissionPolicy;
import aussie.core.service.auth.ServiceAuthorizationService;
import aussie.core.service.routing.ServiceRegistrationValidator;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("ServiceRegistry Pub/Sub")
class ServiceRegistryPubSubTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;
    private static final aussie.core.config.RateLimitingConfig PERMISSIVE_RATE_LIMIT_CONFIG =
            TestRateLimitingConfig.permissive();

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
            return 0.0;
        }
    };

    private InMemoryServiceRegistrationRepository repository;
    private ServiceRegistry registry;
    private InMemoryServiceConfigEventPublisher eventPublisher;
    private final List<ServiceConfigEvent> receivedEvents = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG, PERMISSIVE_RATE_LIMIT_CONFIG);
        var authService = new ServiceAuthorizationService(new DefaultPermissionPolicy());
        repository = new InMemoryServiceRegistrationRepository();
        eventPublisher = new InMemoryServiceConfigEventPublisher();
        eventPublisher.subscribe().subscribe().with(receivedEvents::add);

        registry = new ServiceRegistry(
                repository, NoOpConfigurationCache.INSTANCE, validator, authService, eventPublisher, TEST_CACHE_CONFIG);
    }

    @Nested
    @DisplayName("Event publishing on registration")
    class RegistrationEvents {

        @Test
        @DisplayName("publishes ServiceChanged on new registration")
        void publishesChangedOnRegister() {
            var service = createService("test-service");
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
            assertEquals(1, receivedEvents.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, receivedEvents.getFirst());
            assertEquals("test-service", event.serviceId());
        }

        @Test
        @DisplayName("publishes ServiceChanged on update")
        void publishesChangedOnUpdate() {
            var service = createService("test-service");
            registry.register(service).await().atMost(TIMEOUT);
            receivedEvents.clear();

            var updated = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .version(2)
                    .build();
            var result = registry.register(updated).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
            assertEquals(1, receivedEvents.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, receivedEvents.getFirst());
            assertEquals("test-service", event.serviceId());
        }

        @Test
        @DisplayName("does not publish event on failed registration")
        void doesNotPublishOnFailedRegistration() {
            // version != 1 for new service → failure
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .version(5)
                    .build();
            var result = registry.register(service).await().atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            assertTrue(receivedEvents.isEmpty());
        }
    }

    @Nested
    @DisplayName("Event publishing on update")
    class UpdateEvents {

        @Test
        @DisplayName("publishes ServiceChanged on direct update")
        void publishesChangedOnDirectUpdate() {
            var service = createService("test-service");
            registry.register(service).await().atMost(TIMEOUT);
            receivedEvents.clear();

            var updated = ServiceRegistration.builder("test-service")
                    .baseUrl("http://localhost:9090")
                    .endpoints(List.of())
                    .version(2)
                    .build();
            registry.update(updated).await().atMost(TIMEOUT);

            assertEquals(1, receivedEvents.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, receivedEvents.getFirst());
            assertEquals("test-service", event.serviceId());
        }
    }

    @Nested
    @DisplayName("Event publishing on unregistration")
    class UnregistrationEvents {

        @Test
        @DisplayName("publishes ServiceRemoved on unregister")
        void publishesRemovedOnUnregister() {
            var service = createService("test-service");
            registry.register(service).await().atMost(TIMEOUT);
            receivedEvents.clear();

            registry.unregister("test-service").await().atMost(TIMEOUT);

            assertEquals(1, receivedEvents.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, receivedEvents.getFirst());
            assertEquals("test-service", event.serviceId());
        }

        @Test
        @DisplayName("publishes ServiceRemoved on authorized unregister")
        void publishesRemovedOnAuthorizedUnregister() {
            var service = createServiceWithEndpoint("test-service");
            registry.register(service).await().atMost(TIMEOUT);
            receivedEvents.clear();

            var result = registry.unregisterAuthorized("test-service", Set.of("*"))
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Success.class, result);
            assertEquals(1, receivedEvents.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, receivedEvents.getFirst());
            assertEquals("test-service", event.serviceId());
        }

        @Test
        @DisplayName("does not publish event when unregistering nonexistent service")
        void doesNotPublishOnUnregisterNonexistent() {
            registry.unregister("nonexistent").await().atMost(TIMEOUT);

            assertTrue(receivedEvents.isEmpty());
        }

        @Test
        @DisplayName("does not publish event on unauthorized unregister")
        void doesNotPublishOnUnauthorizedUnregister() {
            var service = createServiceWithEndpoint("test-service");
            registry.register(service).await().atMost(TIMEOUT);
            receivedEvents.clear();

            var result = registry.unregisterAuthorized("test-service", Set.of("no-permission"))
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(RegistrationResult.Failure.class, result);
            assertTrue(receivedEvents.isEmpty());
        }
    }

    @Nested
    @DisplayName("Event consumption (cross-instance cache invalidation)")
    class EventConsumption {

        private ServiceRegistry otherInstance;

        @BeforeEach
        void setUp() {
            var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG, PERMISSIVE_RATE_LIMIT_CONFIG);
            var authService = new ServiceAuthorizationService(new DefaultPermissionPolicy());

            // Second registry sharing the same repository and event publisher
            otherInstance = new ServiceRegistry(
                    repository,
                    NoOpConfigurationCache.INSTANCE,
                    validator,
                    authService,
                    eventPublisher,
                    TEST_CACHE_CONFIG);
            otherInstance.initialize().await().atMost(TIMEOUT);
        }

        @Test
        @DisplayName("ServiceChanged event updates compiled routes on receiving instance")
        void serviceChangedUpdatesRoutes() {
            // Register on main registry (publishes ServiceChanged event)
            var service = createServiceWithEndpoint("event-service");
            registry.register(service).await().atMost(TIMEOUT);

            // Other instance should have the route via event-driven update
            await().atMost(2, SECONDS)
                    .untilAsserted(() -> assertTrue(
                            otherInstance.findRoute("/api/test", "GET").isPresent()));
        }

        @Test
        @DisplayName("ServiceRemoved event removes compiled routes on receiving instance")
        void serviceRemovedClearsRoutes() {
            // Register on both instances
            var service = createServiceWithEndpoint("event-service");
            registry.register(service).await().atMost(TIMEOUT);
            await().atMost(2, SECONDS)
                    .untilAsserted(() -> assertTrue(
                            otherInstance.findRoute("/api/test", "GET").isPresent()));

            // Unregister on main registry (publishes ServiceRemoved event)
            registry.unregister("event-service").await().atMost(TIMEOUT);

            // Other instance should no longer have the route
            await().atMost(2, SECONDS)
                    .untilAsserted(() -> assertFalse(
                            otherInstance.findRoute("/api/test", "GET").isPresent()));
        }

        @Test
        @DisplayName("ServiceChanged event for missing service is a no-op")
        void serviceChangedForMissingServiceIsNoOp() {
            otherInstance.initialize().await().atMost(TIMEOUT);

            // Publish a ServiceChanged event for a service that doesn't exist in the repo
            eventPublisher.publishServiceChanged("nonexistent-service").await().atMost(TIMEOUT);

            // Allow time for event processing, then verify no routes were added
            await().during(Duration.ofMillis(200))
                    .atMost(1, SECONDS)
                    .untilAsserted(() -> assertFalse(
                            otherInstance.findRoute("/api/anything", "GET").isPresent()));
        }
    }

    private ServiceRegistration createService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .baseUrl("http://localhost:8080")
                .endpoints(List.of())
                .build();
    }

    private ServiceRegistration createServiceWithEndpoint(String serviceId) {
        var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
        return ServiceRegistration.builder(serviceId)
                .baseUrl("http://localhost:8080")
                .endpoints(List.of(endpoint))
                .build();
    }
}
