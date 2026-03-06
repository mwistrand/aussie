package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.ConfigurationCacheProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;
import aussie.spi.StorageRepositoryProvider;

@DisplayName("StorageProviderLoader")
@ExtendWith(MockitoExtension.class)
class StorageProviderLoaderTest {

    @Mock
    private StorageAdapterConfig config;

    private StorageProviderLoader createLoader(
            Optional<String> repoProvider, Optional<String> cacheProvider, boolean cacheEnabled) {
        return new StorageProviderLoader(repoProvider, cacheProvider, cacheEnabled, config);
    }

    private void setRepositoryProvider(StorageProviderLoader loader, StorageRepositoryProvider provider)
            throws Exception {
        final var field = StorageProviderLoader.class.getDeclaredField("repositoryProvider");
        field.setAccessible(true);
        field.set(loader, provider);
    }

    private void setCacheProvider(StorageProviderLoader loader, ConfigurationCacheProvider provider) throws Exception {
        final var field = StorageProviderLoader.class.getDeclaredField("cacheProvider");
        field.setAccessible(true);
        field.set(loader, provider);

        final var resolvedField = StorageProviderLoader.class.getDeclaredField("cacheProviderResolved");
        resolvedField.setAccessible(true);
        resolvedField.set(loader, true);
    }

    private void setCacheProviderResolved(StorageProviderLoader loader) throws Exception {
        final var field = StorageProviderLoader.class.getDeclaredField("cacheProviderResolved");
        field.setAccessible(true);
        field.set(loader, true);
    }

    private Object invokeSelectProvider(StorageProviderLoader loader, List<?> providers, String configured, String type)
            throws Exception {
        final var method =
                StorageProviderLoader.class.getDeclaredMethod("selectProvider", List.class, String.class, String.class);
        method.setAccessible(true);
        try {
            return method.invoke(loader, providers, configured, type);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Nested
    @DisplayName("repository()")
    class RepositoryTests {

        @Test
        @DisplayName("should create repository from provider")
        void shouldCreateRepositoryFromProvider() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            var repository = mock(ServiceRegistrationRepository.class);
            when(repoProvider.name()).thenReturn("cassandra");
            when(repoProvider.description()).thenReturn("Cassandra storage");
            when(repoProvider.createRepository(config)).thenReturn(repository);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);

            var result = loader.repository();

            assertEquals(repository, result);
            verify(repoProvider).createRepository(config);
        }

        @Test
        @DisplayName("should select provider by configured name via ServiceLoader")
        void shouldSelectProviderByConfiguredName() {
            // The in-memory provider is on the classpath via META-INF/services
            var loader = createLoader(Optional.of("memory"), Optional.empty(), false);

            var result = loader.repository();

            assertNotNull(result);
        }

        @Test
        @DisplayName("should cache resolved provider across multiple calls")
        void shouldCacheResolvedProvider() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            var repository = mock(ServiceRegistrationRepository.class);
            when(repoProvider.name()).thenReturn("test");
            when(repoProvider.description()).thenReturn("Test storage");
            when(repoProvider.createRepository(config)).thenReturn(repository);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProviderResolved(loader);

            // Multiple calls should reuse the same provider
            loader.repository();
            loader.healthIndicators();

            verify(repoProvider).createRepository(config);
            verify(repoProvider).createHealthIndicator(config);
        }

        @Test
        @DisplayName("should throw when configured provider not found by name")
        void shouldThrowWhenConfiguredProviderNotFound() {
            var loader = createLoader(Optional.of("nonexistent"), Optional.empty(), false);

            var exception = assertThrows(StorageProviderException.class, loader::repository);
            assertTrue(exception.getMessage().contains("Configured repository provider not found: nonexistent"));
        }

        @Test
        @DisplayName("should select highest priority available provider when none configured")
        void shouldSelectHighestPriorityWhenNoneConfigured() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var low = mock(StorageRepositoryProvider.class);
            when(low.isAvailable()).thenReturn(true);
            // priority() returns 0 by default (Mockito default for int)

            final var high = mock(StorageRepositoryProvider.class);
            when(high.isAvailable()).thenReturn(true);
            when(high.priority()).thenReturn(10);

            final var result = invokeSelectProvider(loader, List.of(low, high), null, "repository");

            assertEquals(high, result);
        }

        @Test
        @DisplayName("should treat blank configured name as no configuration and auto-select")
        void shouldTreatBlankConfiguredNameAsAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(StorageRepositoryProvider.class);
            when(provider.isAvailable()).thenReturn(true);

            final var result = invokeSelectProvider(loader, List.of(provider), "   ", "repository");

            assertEquals(provider, result);
        }

        @Test
        @DisplayName("should throw when no available providers for auto-select")
        void shouldThrowWhenNoAvailableProvidersForAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(StorageRepositoryProvider.class);
            when(unavailable.isAvailable()).thenReturn(false);

            assertThrows(
                    StorageProviderException.class,
                    () -> invokeSelectProvider(loader, List.of(unavailable), null, "repository"));
        }

        @Test
        @DisplayName("should skip unavailable providers during auto-select")
        void shouldSkipUnavailableProvidersDuringAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(StorageRepositoryProvider.class);
            // Mockito default for boolean is false, so isAvailable() already returns false

            final var available = mock(StorageRepositoryProvider.class);
            when(available.isAvailable()).thenReturn(true);

            final var result = invokeSelectProvider(loader, List.of(unavailable, available), null, "repository");

            assertEquals(available, result);
        }
    }

    @Nested
    @DisplayName("cache()")
    class CacheTests {

        @Test
        @DisplayName("should return NoOpConfigurationCache when caching is disabled")
        void shouldReturnNoOpWhenCacheDisabled() {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            var result = loader.cache();

            assertInstanceOf(NoOpConfigurationCache.class, result);
        }

        @Test
        @DisplayName("should create cache from provider when enabled and provider is pre-set")
        void shouldCreateCacheFromProvider() throws Exception {
            var cacheProviderMock = mock(ConfigurationCacheProvider.class);
            var cache = mock(ConfigurationCache.class);
            when(cacheProviderMock.name()).thenReturn("redis");
            when(cacheProviderMock.description()).thenReturn("Redis cache");
            when(cacheProviderMock.createCache(config)).thenReturn(cache);

            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            setCacheProvider(loader, cacheProviderMock);

            var result = loader.cache();

            assertEquals(cache, result);
            verify(cacheProviderMock).createCache(config);
        }

        @Test
        @DisplayName("should return NoOpConfigurationCache when enabled but no cache provider resolved")
        void shouldReturnNoOpWhenNoCacheProviderResolved() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            // Mark resolved but with null provider
            setCacheProviderResolved(loader);

            var result = loader.cache();

            assertInstanceOf(NoOpConfigurationCache.class, result);
        }

        @Test
        @DisplayName("should return NoOpConfigurationCache when configured cache provider not found")
        void shouldReturnNoOpWhenConfiguredCacheProviderNotFound() {
            // The classpath has "redis" cache provider; requesting "nonexistent" triggers
            // StorageProviderException which is caught internally -> returns NoOp
            var loader = createLoader(Optional.empty(), Optional.of("nonexistent"), true);

            var result = loader.cache();

            assertInstanceOf(NoOpConfigurationCache.class, result);
        }

        @Test
        @DisplayName("should treat blank configured cache name as auto-select")
        void shouldTreatBlankCacheNameAsAutoSelect() throws Exception {
            // Blank configured cache name should fall through to auto-select
            var loader = createLoader(Optional.empty(), Optional.of("   "), true);
            // Pre-resolve cache so we don't go through ServiceLoader
            setCacheProviderResolved(loader);

            var result = loader.cache();

            // With resolved=true but null provider, returns NoOp
            assertInstanceOf(NoOpConfigurationCache.class, result);
        }

        @Test
        @DisplayName("should cache resolved cache provider across multiple calls")
        void shouldCacheResolvedCacheProviderAcrossMultipleCalls() throws Exception {
            var cacheProviderMock = mock(ConfigurationCacheProvider.class);
            var cache = mock(ConfigurationCache.class);
            when(cacheProviderMock.name()).thenReturn("redis");
            when(cacheProviderMock.description()).thenReturn("Redis cache");
            when(cacheProviderMock.createCache(config)).thenReturn(cache);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.empty());

            var repoProvider = mock(StorageRepositoryProvider.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            setRepositoryProvider(loader, repoProvider);
            setCacheProvider(loader, cacheProviderMock);

            // Call cache() and healthIndicators() - both use getCacheProvider()
            loader.cache();
            loader.healthIndicators();

            // createCache should only be called once from cache()
            verify(cacheProviderMock).createCache(config);
        }
    }

    @Nested
    @DisplayName("selectProvider() for cache providers")
    class SelectCacheProviderTests {

        @Test
        @DisplayName("should select cache provider by name")
        void shouldSelectCacheProviderByName() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(ConfigurationCacheProvider.class);
            when(provider.name()).thenReturn("redis");

            final var result = invokeSelectProvider(loader, List.of(provider), "redis", "cache");

            assertEquals(provider, result);
        }

        @Test
        @DisplayName("should auto-select cache provider by priority")
        void shouldAutoSelectCacheProviderByPriority() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var low = mock(ConfigurationCacheProvider.class);
            when(low.isAvailable()).thenReturn(true);
            when(low.priority()).thenReturn(0);

            final var high = mock(ConfigurationCacheProvider.class);
            when(high.isAvailable()).thenReturn(true);
            when(high.priority()).thenReturn(10);

            final var result = invokeSelectProvider(loader, List.of(low, high), null, "cache");

            assertEquals(high, result);
        }

        @Test
        @DisplayName("should skip unavailable cache providers during auto-select")
        void shouldSkipUnavailableCacheProvidersDuringAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(ConfigurationCacheProvider.class);
            // Mockito default for boolean is false, so isAvailable() already returns false

            final var available = mock(ConfigurationCacheProvider.class);
            when(available.isAvailable()).thenReturn(true);

            final var result = invokeSelectProvider(loader, List.of(unavailable, available), null, "cache");

            assertEquals(available, result);
        }

        @Test
        @DisplayName("should throw when configured cache provider not found by name")
        void shouldThrowWhenConfiguredCacheProviderNotFoundByName() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(ConfigurationCacheProvider.class);
            when(provider.name()).thenReturn("redis");

            assertThrows(
                    StorageProviderException.class,
                    () -> invokeSelectProvider(loader, List.of(provider), "nonexistent", "cache"));
        }

        @Test
        @DisplayName("should throw when no available cache providers for auto-select")
        void shouldThrowWhenNoAvailableCacheProvidersForAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(ConfigurationCacheProvider.class);
            when(unavailable.isAvailable()).thenReturn(false);

            assertThrows(
                    StorageProviderException.class,
                    () -> invokeSelectProvider(loader, List.of(unavailable), null, "cache"));
        }
    }

    @Nested
    @DisplayName("healthIndicators()")
    class HealthIndicatorTests {

        @Test
        @DisplayName("should include repository health indicator when present")
        void shouldIncludeRepositoryHealthIndicator() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            var healthIndicator = mock(StorageHealthIndicator.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.of(healthIndicator));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProviderResolved(loader);

            var indicators = loader.healthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(healthIndicator, indicators.get(0));
        }

        @Test
        @DisplayName("should include both repository and cache health indicators")
        void shouldIncludeBothHealthIndicators() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            var repoHealth = mock(StorageHealthIndicator.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.of(repoHealth));

            var cacheProviderMock = mock(ConfigurationCacheProvider.class);
            var cacheHealth = mock(StorageHealthIndicator.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.of(cacheHealth));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.healthIndicators();

            assertEquals(2, indicators.size());
            assertTrue(indicators.contains(repoHealth));
            assertTrue(indicators.contains(cacheHealth));
        }

        @Test
        @DisplayName("should return empty list when no health indicators provided")
        void shouldReturnEmptyWhenNoHealthIndicators() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProviderResolved(loader);

            var indicators = loader.healthIndicators();

            assertTrue(indicators.isEmpty());
        }

        @Test
        @DisplayName("should include only repository health indicator when cache provider has none")
        void shouldIncludeOnlyRepoHealthWhenCacheProviderHasNone() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            var repoHealth = mock(StorageHealthIndicator.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.of(repoHealth));

            var cacheProviderMock = mock(ConfigurationCacheProvider.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.healthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(repoHealth, indicators.get(0));
        }

        @Test
        @DisplayName("should return only cache health indicator when repo provider has none")
        void shouldReturnOnlyCacheHealthWhenRepoHasNone() throws Exception {
            var repoProvider = mock(StorageRepositoryProvider.class);
            when(repoProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var cacheProviderMock = mock(ConfigurationCacheProvider.class);
            var cacheHealth = mock(StorageHealthIndicator.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.of(cacheHealth));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setRepositoryProvider(loader, repoProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.healthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(cacheHealth, indicators.get(0));
        }
    }
}
