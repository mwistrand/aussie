package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.cache.LocalCacheConfig;
import aussie.core.config.SamplingConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.sampling.EffectiveSamplingRate.SamplingSource;
import aussie.core.model.sampling.EndpointSamplingConfig;
import aussie.core.model.sampling.ServiceSamplingConfig;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.SamplingConfigRepository;

@DisplayName("SamplingResolver")
class SamplingResolverTest {

    private SamplingResolver resolver;
    private SamplingConfig config;
    private SamplingConfig.LookupConfig lookupConfig;
    private SamplingConfigRepository repository;

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
        config = mock(SamplingConfig.class);
        lookupConfig = mock(SamplingConfig.LookupConfig.class);
        repository = mock(SamplingConfigRepository.class);

        // Default platform config
        when(config.enabled()).thenReturn(true);
        when(config.defaultRate()).thenReturn(1.0);
        when(config.minimumRate()).thenReturn(0.0);
        when(config.maximumRate()).thenReturn(1.0);
        when(config.lookup()).thenReturn(lookupConfig);
        when(lookupConfig.timeout()).thenReturn(Duration.ofSeconds(5));

        resolver = new SamplingResolver(config, repository, TEST_CACHE_CONFIG);
    }

    private ServiceRegistration createService(String serviceId, ServiceSamplingConfig samplingConfig) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8081"))
                .endpoints(List.of())
                .samplingConfig(samplingConfig)
                .build();
    }

    @Nested
    @DisplayName("getCachedPlatformDefault")
    class GetCachedPlatformDefault {

        @Test
        @DisplayName("should return platform default rate")
        void shouldReturnPlatformDefaultRate() {
            when(config.defaultRate()).thenReturn(0.5);
            // Need new resolver with new config values
            resolver = new SamplingResolver(config, repository, TEST_CACHE_CONFIG);

            var rate = resolver.getCachedPlatformDefault();

            assertEquals(0.5, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should clamp to platform bounds")
        void shouldClampToPlatformBounds() {
            when(config.defaultRate()).thenReturn(0.3);
            when(config.minimumRate()).thenReturn(0.5);
            // Need new resolver with new config values
            resolver = new SamplingResolver(config, repository, TEST_CACHE_CONFIG);

            var rate = resolver.getCachedPlatformDefault();

            assertEquals(0.5, rate.rate()); // Clamped to minimum
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should cache and reuse the same instance")
        void shouldCacheAndReuseInstance() {
            var rate1 = resolver.getCachedPlatformDefault();
            var rate2 = resolver.getCachedPlatformDefault();

            assertSame(rate1, rate2);
        }
    }

    @Nested
    @DisplayName("resolveRate with RouteLookupResult")
    class ResolveRateWithRoute {

        @Test
        @DisplayName("should use platform defaults when service has no sampling config")
        void shouldUsePlatformDefaultsWhenNoServiceConfig() {
            var service = createService("test-service", null);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should use service config when available")
        void shouldUseServiceConfigWhenAvailable() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            var service = createService("test-service", serviceConfig);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(0.5, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("should use endpoint config over service config")
        void shouldUseEndpointConfigOverServiceConfig() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            var service = createService("test-service", serviceConfig);
            var endpointSamplingConfig = EndpointSamplingConfig.of(0.25);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.of(endpointSamplingConfig),
                    Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(0.25, rate.rate());
            assertEquals(SamplingSource.ENDPOINT, rate.source());
        }

        @Test
        @DisplayName("should clamp to platform minimum")
        void shouldClampToPlatformMinimum() {
            when(config.minimumRate()).thenReturn(0.1);

            var serviceConfig = ServiceSamplingConfig.of(0.05);
            var service = createService("test-service", serviceConfig);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(0.1, rate.rate()); // Clamped to minimum
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("should clamp to platform maximum")
        void shouldClampToPlatformMaximum() {
            when(config.maximumRate()).thenReturn(0.8);

            var serviceConfig = ServiceSamplingConfig.noSampling(); // 1.0
            var service = createService("test-service", serviceConfig);
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(0.8, rate.rate()); // Clamped to maximum
            assertEquals(SamplingSource.SERVICE, rate.source());
        }
    }

    @Nested
    @DisplayName("resolveServiceRate")
    class ResolveServiceRate {

        @Test
        @DisplayName("should use service config")
        void shouldUseServiceConfig() {
            var serviceConfig = ServiceSamplingConfig.of(0.75);
            var service = createService("test-service", serviceConfig);

            var rate = resolver.resolveServiceRate(service);

            assertEquals(0.75, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("should use platform defaults when no service config")
        void shouldUsePlatformDefaultsWhenNoServiceConfig() {
            var service = createService("test-service", null);

            var rate = resolver.resolveServiceRate(service);

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }
    }

    @Nested
    @DisplayName("No sampling config")
    class NoSamplingConfig {

        @Test
        @DisplayName("noSampling should set rate to 1.0")
        void noSamplingShouldSetRateToOne() {
            var serviceConfig = ServiceSamplingConfig.noSampling();
            var service = createService("test-service", serviceConfig);

            var rate = resolver.resolveServiceRate(service);

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("endpoint noSampling should override service config")
        void endpointNoSamplingShouldOverrideServiceConfig() {
            var serviceConfig = ServiceSamplingConfig.of(0.1);
            var service = createService("test-service", serviceConfig);
            var endpointSamplingConfig = EndpointSamplingConfig.noSampling();
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.of(endpointSamplingConfig),
                    Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", java.util.Map.of());

            var rate = resolver.resolveRate(route);

            assertEquals(1.0, rate.rate()); // No sampling = 100% trace rate
            assertEquals(SamplingSource.ENDPOINT, rate.source());
        }
    }

    @Nested
    @DisplayName("Helper methods")
    class HelperMethods {

        @Test
        @DisplayName("isEnabled should return config value")
        void isEnabledShouldReturnConfigValue() {
            when(config.enabled()).thenReturn(true);
            assertEquals(true, resolver.isEnabled());

            when(config.enabled()).thenReturn(false);
            assertEquals(false, resolver.isEnabled());
        }

        @Test
        @DisplayName("getDefaultRate should return config value")
        void getDefaultRateShouldReturnConfigValue() {
            when(config.defaultRate()).thenReturn(0.75);
            assertEquals(0.75, resolver.getDefaultRate());
        }

        @Test
        @DisplayName("getMinimumRate should return config value")
        void getMinimumRateShouldReturnConfigValue() {
            when(config.minimumRate()).thenReturn(0.1);
            assertEquals(0.1, resolver.getMinimumRate());
        }

        @Test
        @DisplayName("getMaximumRate should return config value")
        void getMaximumRateShouldReturnConfigValue() {
            when(config.maximumRate()).thenReturn(0.9);
            assertEquals(0.9, resolver.getMaximumRate());
        }
    }

    @Nested
    @DisplayName("resolveByServiceId")
    class ResolveByServiceId {

        @Test
        @DisplayName("should return platform default for null service ID")
        void shouldReturnPlatformDefaultForNullServiceId() {
            var rate = resolver.resolveByServiceId(null);

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
            verify(repository, never()).findByServiceId(null);
        }

        @Test
        @DisplayName("should return platform default for 'unknown' service ID")
        void shouldReturnPlatformDefaultForUnknownServiceId() {
            var rate = resolver.resolveByServiceId("unknown");

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
            verify(repository, never()).findByServiceId("unknown");
        }

        @Test
        @DisplayName("should look up service from repository on cache miss")
        void shouldLookUpServiceFromRepositoryOnCacheMiss() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            when(repository.findByServiceId("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            var rate = resolver.resolveByServiceId("my-service");

            assertEquals(0.5, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
            verify(repository).findByServiceId("my-service");
        }

        @Test
        @DisplayName("should return platform default when service not found")
        void shouldReturnPlatformDefaultWhenServiceNotFound() {
            when(repository.findByServiceId("nonexistent"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var rate = resolver.resolveByServiceId("nonexistent");

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should return platform default on repository error")
        void shouldReturnPlatformDefaultOnRepositoryError() {
            when(repository.findByServiceId("error-service"))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Database unavailable")));

            var rate = resolver.resolveByServiceId("error-service");

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should use cached value on subsequent lookups")
        void shouldUseCachedValueOnSubsequentLookups() {
            var serviceConfig = ServiceSamplingConfig.of(0.3);
            when(repository.findByServiceId("cached-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            // First call - should hit repository
            var rate1 = resolver.resolveByServiceId("cached-service");
            // Second call - should use cache
            var rate2 = resolver.resolveByServiceId("cached-service");

            assertEquals(0.3, rate1.rate());
            assertEquals(0.3, rate2.rate());
            // Repository should only be called once
            verify(repository, times(1)).findByServiceId("cached-service");
        }

        @Test
        @DisplayName("should clamp to platform bounds")
        void shouldClampToPlatformBounds() {
            when(config.minimumRate()).thenReturn(0.2);
            var serviceConfig = ServiceSamplingConfig.of(0.1); // Below minimum
            when(repository.findByServiceId("low-rate-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            var rate = resolver.resolveByServiceId("low-rate-service");

            assertEquals(0.2, rate.rate()); // Clamped to minimum
            assertEquals(SamplingSource.SERVICE, rate.source());
        }
    }

    @Nested
    @DisplayName("resolveByServiceIdNonBlocking")
    class ResolveByServiceIdNonBlocking {

        @Test
        @DisplayName("should return platform default for null service ID")
        void shouldReturnPlatformDefaultForNullServiceId() {
            var rate = resolver.resolveByServiceIdNonBlocking(null);

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should return platform default for 'unknown' service ID")
        void shouldReturnPlatformDefaultForUnknownServiceId() {
            var rate = resolver.resolveByServiceIdNonBlocking("unknown");

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should return platform default immediately on cache miss")
        void shouldReturnPlatformDefaultImmediatelyOnCacheMiss() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            when(repository.findByServiceId("new-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            // First call returns platform default immediately
            var rate = resolver.resolveByServiceIdNonBlocking("new-service");

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should return cached value if present")
        void shouldReturnCachedValueIfPresent() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            when(repository.findByServiceId("cached-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            // Pre-populate cache via blocking method
            resolver.resolveByServiceId("cached-service");

            // Non-blocking should return cached value
            var rate = resolver.resolveByServiceIdNonBlocking("cached-service");

            assertEquals(0.5, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }
    }

    @Nested
    @DisplayName("resolveByServiceIdAsync")
    class ResolveByServiceIdAsync {

        @Test
        @DisplayName("should return platform default for null service ID")
        void shouldReturnPlatformDefaultForNullServiceId() {
            var rate = resolver.resolveByServiceIdAsync(null).await().indefinitely();

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should return platform default for 'unknown' service ID")
        void shouldReturnPlatformDefaultForUnknownServiceId() {
            var rate = resolver.resolveByServiceIdAsync("unknown").await().indefinitely();

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should look up service asynchronously")
        void shouldLookUpServiceAsynchronously() {
            var serviceConfig = ServiceSamplingConfig.of(0.6);
            when(repository.findByServiceId("async-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            var rate = resolver.resolveByServiceIdAsync("async-service").await().indefinitely();

            assertEquals(0.6, rate.rate());
            assertEquals(SamplingSource.SERVICE, rate.source());
        }

        @Test
        @DisplayName("should recover with platform default on error")
        void shouldRecoverWithPlatformDefaultOnError() {
            when(repository.findByServiceId("error-service"))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Timeout")));

            var rate = resolver.resolveByServiceIdAsync("error-service").await().indefinitely();

            assertEquals(1.0, rate.rate());
            assertEquals(SamplingSource.PLATFORM, rate.source());
        }

        @Test
        @DisplayName("should cache result for future lookups")
        void shouldCacheResultForFutureLookups() {
            var serviceConfig = ServiceSamplingConfig.of(0.4);
            when(repository.findByServiceId("async-cached"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            // Async lookup
            resolver.resolveByServiceIdAsync("async-cached").await().indefinitely();
            // Sync lookup should use cache
            var rate = resolver.resolveByServiceId("async-cached");

            assertEquals(0.4, rate.rate());
            // Only one repository call
            verify(repository, times(1)).findByServiceId("async-cached");
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    class InvalidateCache {

        @Test
        @DisplayName("should force re-lookup after invalidation")
        void shouldForceReLookupAfterInvalidation() {
            var serviceConfig = ServiceSamplingConfig.of(0.5);
            when(repository.findByServiceId("invalidate-test"))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceConfig)));

            // First lookup - caches result
            resolver.resolveByServiceId("invalidate-test");
            // Invalidate cache
            resolver.invalidateCache("invalidate-test");
            // Second lookup - should hit repository again
            resolver.resolveByServiceId("invalidate-test");

            // Repository called twice due to invalidation
            verify(repository, times(2)).findByServiceId("invalidate-test");
        }

        @Test
        @DisplayName("should not affect other cached entries")
        void shouldNotAffectOtherCachedEntries() {
            when(repository.findByServiceId("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.of(ServiceSamplingConfig.of(0.3))));
            when(repository.findByServiceId("service-2"))
                    .thenReturn(Uni.createFrom().item(Optional.of(ServiceSamplingConfig.of(0.7))));

            // Cache both services
            resolver.resolveByServiceId("service-1");
            resolver.resolveByServiceId("service-2");

            // Invalidate only service-1
            resolver.invalidateCache("service-1");

            // Look up both again
            resolver.resolveByServiceId("service-1");
            resolver.resolveByServiceId("service-2");

            // service-1 should be called twice, service-2 only once
            verify(repository, times(2)).findByServiceId("service-1");
            verify(repository, times(1)).findByServiceId("service-2");
        }
    }
}
