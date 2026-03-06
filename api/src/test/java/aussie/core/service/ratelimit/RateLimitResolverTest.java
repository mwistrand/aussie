package aussie.core.service.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.cache.LocalCacheConfig;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("RateLimitResolver")
class RateLimitResolverTest {

    private RateLimitingConfig config;
    private ServiceRegistry serviceRegistry;
    private LocalCacheConfig cacheConfig;
    private RateLimitResolver resolver;

    @BeforeEach
    void setUp() {
        config = mock(RateLimitingConfig.class);
        serviceRegistry = mock(ServiceRegistry.class);
        cacheConfig = mock(LocalCacheConfig.class);

        when(config.defaultRequestsPerWindow()).thenReturn(100L);
        when(config.windowSeconds()).thenReturn(60L);
        when(config.burstCapacity()).thenReturn(100L);
        when(config.platformMaxRequestsPerWindow()).thenReturn(Long.MAX_VALUE);
        when(config.platformMaxWindowSeconds()).thenReturn(Long.MAX_VALUE);
        when(config.enabled()).thenReturn(true);

        when(cacheConfig.rateLimitConfigTtl()).thenReturn(Duration.ofSeconds(30));
        when(cacheConfig.maxEntries()).thenReturn(10000L);
        when(cacheConfig.jitterFactor()).thenReturn(0.0);

        resolver = new RateLimitResolver(config, serviceRegistry, cacheConfig);
    }

    private ServiceRegistration testService(ServiceRateLimitConfig rateLimitConfig) {
        return ServiceRegistration.builder("test-service")
                .displayName("Test")
                .baseUrl(URI.create("http://localhost:8080"))
                .rateLimitConfig(rateLimitConfig)
                .build();
    }

    private RouteMatch routeMatchWith(
            Optional<ServiceRateLimitConfig> serviceConfig, Optional<EndpointRateLimitConfig> endpointConfig) {
        var endpoint = new EndpointConfig(
                "/api/test",
                Set.of("GET"),
                EndpointVisibility.PUBLIC,
                Optional.empty(),
                false,
                aussie.core.model.routing.EndpointType.HTTP,
                endpointConfig);
        var service = ServiceRegistration.builder("test-service")
                .displayName("Test")
                .baseUrl(URI.create("http://localhost:8080"))
                .rateLimitConfig(serviceConfig.orElse(null))
                .build();
        return new RouteMatch(service, endpoint, "/api/test", Map.of());
    }

    @Nested
    @DisplayName("resolveLimit(RouteLookupResult)")
    class ResolveLimitTests {

        @Test
        @DisplayName("should use platform defaults when no overrides")
        void shouldUsePlatformDefaults() {
            var route = routeMatchWith(Optional.empty(), Optional.empty());

            var result = resolver.resolveLimit(route);

            assertEquals(100L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds());
            assertEquals(100L, result.burstCapacity());
        }

        @Test
        @DisplayName("should apply service-level overrides")
        void shouldApplyServiceLevelOverrides() {
            var serviceConfig = ServiceRateLimitConfig.of(50, 30, 75);
            var route = routeMatchWith(Optional.of(serviceConfig), Optional.empty());

            var result = resolver.resolveLimit(route);

            assertEquals(50L, result.requestsPerWindow());
            assertEquals(30L, result.windowSeconds());
            assertEquals(75L, result.burstCapacity());
        }

        @Test
        @DisplayName("should apply endpoint-level overrides over service")
        void shouldApplyEndpointLevelOverrides() {
            var serviceConfig = ServiceRateLimitConfig.of(50, 30, 75);
            var endpointConfig = new EndpointRateLimitConfig(Optional.of(25L), Optional.of(10L), Optional.of(30L));
            var route = routeMatchWith(Optional.of(serviceConfig), Optional.of(endpointConfig));

            var result = resolver.resolveLimit(route);

            assertEquals(25L, result.requestsPerWindow());
            assertEquals(10L, result.windowSeconds());
            assertEquals(30L, result.burstCapacity());
        }

        @Test
        @DisplayName("should cap at platform maximum")
        void shouldCapAtPlatformMaximum() {
            when(config.platformMaxRequestsPerWindow()).thenReturn(200L);
            when(config.platformMaxWindowSeconds()).thenReturn(120L);
            resolver = new RateLimitResolver(config, serviceRegistry, cacheConfig);

            var serviceConfig = ServiceRateLimitConfig.of(500, 300, 500);
            var route = routeMatchWith(Optional.of(serviceConfig), Optional.empty());

            var result = resolver.resolveLimit(route);

            assertEquals(200L, result.requestsPerWindow());
            assertEquals(120L, result.windowSeconds());
            assertEquals(200L, result.burstCapacity());
        }

        @Test
        @DisplayName("should use partial service overrides with platform defaults")
        void shouldUsePartialServiceOverrides() {
            var serviceConfig = new ServiceRateLimitConfig(Optional.of(50L), Optional.empty(), Optional.empty());
            var route = routeMatchWith(Optional.of(serviceConfig), Optional.empty());

            var result = resolver.resolveLimit(route);

            assertEquals(50L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds()); // platform default
            assertEquals(100L, result.burstCapacity()); // platform default
        }
    }

    @Nested
    @DisplayName("resolveByServiceId()")
    class ResolveByServiceIdTests {

        @Test
        @DisplayName("should return platform defaults for null serviceId")
        void shouldReturnPlatformDefaultsForNull() {
            var result = resolver.resolveByServiceId(null).await().atMost(Duration.ofSeconds(1));

            assertEquals(100L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds());
        }

        @Test
        @DisplayName("should return platform defaults for 'unknown' serviceId")
        void shouldReturnPlatformDefaultsForUnknown() {
            var result = resolver.resolveByServiceId("unknown").await().atMost(Duration.ofSeconds(1));

            assertEquals(100L, result.requestsPerWindow());
        }

        @Test
        @DisplayName("should look up service config from registry on cache miss")
        void shouldLookUpServiceConfigOnCacheMiss() {
            var rateLimitConfig = ServiceRateLimitConfig.of(200, 120, 200);
            var service = testService(rateLimitConfig);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));

            var result = resolver.resolveByServiceId("my-service").await().atMost(Duration.ofSeconds(1));

            assertEquals(200L, result.requestsPerWindow());
            assertEquals(120L, result.windowSeconds());
        }

        @Test
        @DisplayName("should return platform defaults when service not found")
        void shouldReturnPlatformDefaultsWhenServiceNotFound() {
            when(serviceRegistry.getServiceForRateLimiting("unknown-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = resolver.resolveByServiceId("unknown-service").await().atMost(Duration.ofSeconds(1));

            assertEquals(100L, result.requestsPerWindow());
        }
    }

    @Nested
    @DisplayName("resolveServiceLimit()")
    class ResolveServiceLimitTests {

        @Test
        @DisplayName("should resolve service rate limit")
        void shouldResolveServiceRateLimit() {
            var rateLimitConfig = ServiceRateLimitConfig.of(75, 30, 100);
            var service = testService(rateLimitConfig);

            var result = resolver.resolveServiceLimit(service);

            assertEquals(75L, result.requestsPerWindow());
            assertEquals(30L, result.windowSeconds());
            assertEquals(100L, result.burstCapacity());
        }

        @Test
        @DisplayName("should use platform defaults when no service config")
        void shouldUsePlatformDefaultsWhenNoServiceConfig() {
            var service = testService(null);

            var result = resolver.resolveServiceLimit(service);

            assertEquals(100L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds());
        }
    }

    @Nested
    @DisplayName("resolvePlatformDefaults()")
    class ResolvePlatformDefaultsTests {

        @Test
        @DisplayName("should return configured platform defaults")
        void shouldReturnConfiguredPlatformDefaults() {
            var result = resolver.resolvePlatformDefaults();

            assertEquals(100L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds());
            assertEquals(100L, result.burstCapacity());
        }
    }

    @Nested
    @DisplayName("resolveWebSocketConnectionLimit()")
    class WebSocketConnectionLimitTests {

        @BeforeEach
        void setUp() {
            var wsConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.class);
            var connConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig.class);
            when(wsConfig.connection()).thenReturn(connConfig);
            when(connConfig.requestsPerWindow()).thenReturn(10L);
            when(connConfig.windowSeconds()).thenReturn(60L);
            when(connConfig.burstCapacity()).thenReturn(5L);
            when(config.websocket()).thenReturn(wsConfig);

            // Also need message config for the resolver constructor
            var msgConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.MessageConfig.class);
            when(wsConfig.message()).thenReturn(msgConfig);
            when(msgConfig.requestsPerWindow()).thenReturn(100L);
            when(msgConfig.windowSeconds()).thenReturn(1L);
            when(msgConfig.burstCapacity()).thenReturn(50L);

            resolver = new RateLimitResolver(config, serviceRegistry, cacheConfig);
        }

        @Test
        @DisplayName("should return platform WebSocket defaults when no service")
        void shouldReturnPlatformDefaults() {
            var result = resolver.resolveWebSocketConnectionLimit(Optional.empty());

            assertEquals(10L, result.requestsPerWindow());
            assertEquals(60L, result.windowSeconds());
            assertEquals(5L, result.burstCapacity());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return config enabled state")
        void shouldReturnConfigEnabledState() {
            when(config.enabled()).thenReturn(true);
            assertEquals(true, resolver.isEnabled());
        }
    }

    @Nested
    @DisplayName("invalidateCache()")
    class InvalidateCacheTests {

        @Test
        @DisplayName("should invalidate cached entry")
        void shouldInvalidateCachedEntry() {
            var rateLimitConfig = ServiceRateLimitConfig.of(200, 120, 200);
            var service = testService(rateLimitConfig);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));

            // Prime the cache
            resolver.resolveByServiceId("my-service").await().atMost(Duration.ofSeconds(1));

            // Invalidate and re-fetch with different config
            resolver.invalidateCache("my-service");
            var updatedConfig = ServiceRateLimitConfig.of(500, 300, 500);
            var updatedService = testService(updatedConfig);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(updatedService)));

            var result = resolver.resolveByServiceId("my-service").await().atMost(Duration.ofSeconds(1));

            assertEquals(500L, result.requestsPerWindow());
        }

        @Test
        @DisplayName("should use cached entry on cache hit")
        void shouldUseCachedEntryOnHit() {
            var rateLimitConfig = ServiceRateLimitConfig.of(200, 120, 200);
            var service = testService(rateLimitConfig);
            when(serviceRegistry.getServiceForRateLimiting("cached-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));

            // First call primes the cache
            resolver.resolveByServiceId("cached-service").await().atMost(Duration.ofSeconds(1));

            // Second call should use cache (change mock to verify it's not called again)
            when(serviceRegistry.getServiceForRateLimiting("cached-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = resolver.resolveByServiceId("cached-service").await().atMost(Duration.ofSeconds(1));

            // Should still return 200 from cache, not 100 (platform defaults)
            assertEquals(200L, result.requestsPerWindow());
            assertEquals(120L, result.windowSeconds());
        }
    }
}
