package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.config.LocalCacheConfig;
import aussie.config.RateLimitingConfig;
import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointRateLimitConfig;
import aussie.core.model.EndpointType;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceOnlyMatch;
import aussie.core.model.ServiceRateLimitConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.ServiceWebSocketRateLimitConfig;
import aussie.core.model.ServiceWebSocketRateLimitConfig.RateLimitValues;

@DisplayName("RateLimitResolver")
class RateLimitResolverTest {

    private RateLimitResolver resolver;
    private RateLimitingConfig config;
    private ServiceRegistry serviceRegistry;

    // Test cache config
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
        config = mock(RateLimitingConfig.class);
        serviceRegistry = mock(ServiceRegistry.class);

        // Default platform config
        when(config.defaultRequestsPerWindow()).thenReturn(100L);
        when(config.windowSeconds()).thenReturn(60L);
        when(config.burstCapacity()).thenReturn(150L);
        when(config.platformMaxRequestsPerWindow()).thenReturn(1000L);
        when(config.enabled()).thenReturn(true);

        // WebSocket config
        var wsConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.class);
        var connConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig.class);
        var msgConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.MessageConfig.class);
        when(config.websocket()).thenReturn(wsConfig);
        when(wsConfig.connection()).thenReturn(connConfig);
        when(wsConfig.message()).thenReturn(msgConfig);
        when(connConfig.requestsPerWindow()).thenReturn(10L);
        when(connConfig.windowSeconds()).thenReturn(60L);
        when(connConfig.burstCapacity()).thenReturn(15L);
        when(msgConfig.requestsPerWindow()).thenReturn(100L);
        when(msgConfig.windowSeconds()).thenReturn(1L);
        when(msgConfig.burstCapacity()).thenReturn(100L);

        resolver = new RateLimitResolver(config, serviceRegistry, TEST_CACHE_CONFIG);
    }

    private ServiceRegistration createService(String serviceId, ServiceRateLimitConfig rateLimitConfig) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8081"))
                .endpoints(List.of())
                .rateLimitConfig(rateLimitConfig)
                .build();
    }

    @Nested
    @DisplayName("resolvePlatformDefaults")
    class ResolvePlatformDefaults {

        @Test
        @DisplayName("should return platform defaults")
        void shouldReturnPlatformDefaults() {
            var limit = resolver.resolvePlatformDefaults();

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(150, limit.burstCapacity());
        }
    }

    @Nested
    @DisplayName("resolveLimit with RouteLookupResult")
    class ResolveLimitWithRoute {

        @Test
        @DisplayName("should use platform defaults when service has no rate limit config")
        void shouldUsePlatformDefaultsWhenNoServiceConfig() {
            var service = createService("test-service", null);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var limit = resolver.resolveLimit(route);

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(150, limit.burstCapacity());
        }

        @Test
        @DisplayName("should use service config when available")
        void shouldUseServiceConfigWhenAvailable() {
            var serviceConfig = ServiceRateLimitConfig.of(50, 30, 75);
            var service = createService("test-service", serviceConfig);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var limit = resolver.resolveLimit(route);

            assertEquals(50, limit.requestsPerWindow());
            assertEquals(30, limit.windowSeconds());
            assertEquals(75, limit.burstCapacity());
        }

        @Test
        @DisplayName("should use endpoint config over service config")
        void shouldUseEndpointConfigOverServiceConfig() {
            var serviceConfig = ServiceRateLimitConfig.of(50, 30, 75);
            var service = createService("test-service", serviceConfig);
            var endpointConfig = EndpointRateLimitConfig.of(25, 15, 40);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.of(endpointConfig));
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var limit = resolver.resolveLimit(route);

            assertEquals(25, limit.requestsPerWindow());
            assertEquals(15, limit.windowSeconds());
            assertEquals(40, limit.burstCapacity());
        }

        @Test
        @DisplayName("should cap at platform maximum")
        void shouldCapAtPlatformMaximum() {
            var serviceConfig = ServiceRateLimitConfig.of(2000, 60, 2500);
            var service = createService("test-service", serviceConfig);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var limit = resolver.resolveLimit(route);

            assertEquals(1000, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(1000, limit.burstCapacity());
        }
    }

    @Nested
    @DisplayName("resolveServiceLimit")
    class ResolveServiceLimit {

        @Test
        @DisplayName("should use service config")
        void shouldUseServiceConfig() {
            var serviceConfig = ServiceRateLimitConfig.of(75, 45, 100);
            var service = createService("test-service", serviceConfig);

            var limit = resolver.resolveServiceLimit(service);

            assertEquals(75, limit.requestsPerWindow());
            assertEquals(45, limit.windowSeconds());
            assertEquals(100, limit.burstCapacity());
        }

        @Test
        @DisplayName("should use platform defaults when no service config")
        void shouldUsePlatformDefaultsWhenNoServiceConfig() {
            var service = createService("test-service", null);

            var limit = resolver.resolveServiceLimit(service);

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(150, limit.burstCapacity());
        }
    }

    @Nested
    @DisplayName("resolveWebSocketConnectionLimit")
    class ResolveWebSocketConnectionLimit {

        @Test
        @DisplayName("should return platform defaults when no service provided")
        void shouldReturnWebSocketConnectionLimits() {
            var limit = resolver.resolveWebSocketConnectionLimit(Optional.empty());

            assertEquals(10, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(15, limit.burstCapacity());
        }

        @Test
        @DisplayName("should return platform defaults when service has no WebSocket config")
        void shouldReturnPlatformDefaultsWhenServiceHasNoWsConfig() {
            var service = createService("test-service", null);

            var limit = resolver.resolveWebSocketConnectionLimit(Optional.of(service));

            assertEquals(10, limit.requestsPerWindow());
            assertEquals(60, limit.windowSeconds());
            assertEquals(15, limit.burstCapacity());
        }

        @Test
        @DisplayName("should apply service-level WebSocket connection config")
        void shouldApplyServiceLevelWsConnectionConfig() {
            var wsConfig =
                    new ServiceWebSocketRateLimitConfig(Optional.of(RateLimitValues.of(20, 30, 25)), Optional.empty());
            var serviceConfig = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));
            var service = createService("test-service", serviceConfig);

            var limit = resolver.resolveWebSocketConnectionLimit(Optional.of(service));

            assertEquals(20, limit.requestsPerWindow());
            assertEquals(30, limit.windowSeconds());
            assertEquals(25, limit.burstCapacity());
        }

        @Test
        @DisplayName("should use platform defaults for unspecified values in service config")
        void shouldUsePlatformDefaultsForUnspecifiedValues() {
            // Only specify requestsPerWindow, use platform defaults for window and burst
            var connectionConfig = new RateLimitValues(Optional.of(50L), Optional.empty(), Optional.empty());
            var wsConfig = new ServiceWebSocketRateLimitConfig(Optional.of(connectionConfig), Optional.empty());
            var serviceConfig = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));
            var service = createService("test-service", serviceConfig);

            var limit = resolver.resolveWebSocketConnectionLimit(Optional.of(service));

            assertEquals(50, limit.requestsPerWindow()); // From service config
            assertEquals(60, limit.windowSeconds()); // Platform default
            assertEquals(15, limit.burstCapacity()); // Platform default
        }
    }

    @Nested
    @DisplayName("resolveWebSocketMessageLimit")
    class ResolveWebSocketMessageLimit {

        @Test
        @DisplayName("should return platform defaults when no service provided")
        void shouldReturnWebSocketMessageLimits() {
            var limit = resolver.resolveWebSocketMessageLimit(Optional.empty());

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(1, limit.windowSeconds());
            assertEquals(100, limit.burstCapacity());
        }

        @Test
        @DisplayName("should return platform defaults when service has no WebSocket config")
        void shouldReturnPlatformDefaultsWhenServiceHasNoWsConfig() {
            var service = createService("test-service", null);

            var limit = resolver.resolveWebSocketMessageLimit(Optional.of(service));

            assertEquals(100, limit.requestsPerWindow());
            assertEquals(1, limit.windowSeconds());
            assertEquals(100, limit.burstCapacity());
        }

        @Test
        @DisplayName("should apply service-level WebSocket message config")
        void shouldApplyServiceLevelWsMessageConfig() {
            var wsConfig =
                    new ServiceWebSocketRateLimitConfig(Optional.empty(), Optional.of(RateLimitValues.of(200, 2, 150)));
            var serviceConfig = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));
            var service = createService("test-service", serviceConfig);

            var limit = resolver.resolveWebSocketMessageLimit(Optional.of(service));

            assertEquals(200, limit.requestsPerWindow());
            assertEquals(2, limit.windowSeconds());
            assertEquals(150, limit.burstCapacity());
        }

        @Test
        @DisplayName("should use platform defaults for unspecified values in service config")
        void shouldUsePlatformDefaultsForUnspecifiedValues() {
            // Only specify burstCapacity, use platform defaults for requests and window
            var messageConfig = new RateLimitValues(Optional.empty(), Optional.empty(), Optional.of(75L));
            var wsConfig = new ServiceWebSocketRateLimitConfig(Optional.empty(), Optional.of(messageConfig));
            var serviceConfig = new ServiceRateLimitConfig(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig));
            var service = createService("test-service", serviceConfig);

            var limit = resolver.resolveWebSocketMessageLimit(Optional.of(service));

            assertEquals(100, limit.requestsPerWindow()); // Platform default
            assertEquals(1, limit.windowSeconds()); // Platform default
            assertEquals(75, limit.burstCapacity()); // From service config
        }
    }

    @Nested
    @DisplayName("Helper methods")
    class HelperMethods {

        @Test
        @DisplayName("isEnabled should return config value")
        void isEnabledShouldReturnConfigValue() {
            assertEquals(true, resolver.isEnabled());

            when(config.enabled()).thenReturn(false);
            assertEquals(false, resolver.isEnabled());
        }

        @Test
        @DisplayName("getPlatformMax should return config value")
        void getPlatformMaxShouldReturnConfigValue() {
            assertEquals(1000, resolver.getPlatformMax());
        }
    }

    @Nested
    @DisplayName("ServiceOnlyMatch resolution")
    class ServiceOnlyMatchResolution {

        @Test
        @DisplayName("should resolve using service defaults for ServiceOnlyMatch")
        void shouldResolveUsingServiceDefaultsForServiceOnlyMatch() {
            var serviceConfig = ServiceRateLimitConfig.of(80, 40, 120);
            var service = createService("test-service", serviceConfig);
            var route = new ServiceOnlyMatch(service);

            var limit = resolver.resolveLimit(route);

            assertEquals(80, limit.requestsPerWindow());
            assertEquals(40, limit.windowSeconds());
            assertEquals(120, limit.burstCapacity());
        }
    }
}
