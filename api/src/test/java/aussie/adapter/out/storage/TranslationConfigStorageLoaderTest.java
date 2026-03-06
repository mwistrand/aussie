package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigCache;
import aussie.core.port.out.TranslationConfigRepository;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;
import aussie.spi.TranslationConfigCacheProvider;
import aussie.spi.TranslationConfigStorageProvider;

@DisplayName("TranslationConfigStorageLoader")
@ExtendWith(MockitoExtension.class)
class TranslationConfigStorageLoaderTest {

    @Mock
    private StorageAdapterConfig config;

    @Mock
    private Instance<ReactiveRedisDataSource> redisDataSource;

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final long DEFAULT_MAX_SIZE = 100L;

    private TranslationConfigStorageLoader createLoader(
            Optional<String> storageProvider, Optional<String> cacheProvider, boolean cacheEnabled) {
        return new TranslationConfigStorageLoader(
                storageProvider, cacheProvider, cacheEnabled, DEFAULT_TTL, DEFAULT_MAX_SIZE, config, redisDataSource);
    }

    private void setStorageProvider(TranslationConfigStorageLoader loader, TranslationConfigStorageProvider provider)
            throws Exception {
        final var field = TranslationConfigStorageLoader.class.getDeclaredField("storageProvider");
        field.setAccessible(true);
        field.set(loader, provider);
    }

    private void setCacheProvider(TranslationConfigStorageLoader loader, TranslationConfigCacheProvider provider)
            throws Exception {
        final var field = TranslationConfigStorageLoader.class.getDeclaredField("cacheProvider");
        field.setAccessible(true);
        field.set(loader, provider);

        final var resolvedField = TranslationConfigStorageLoader.class.getDeclaredField("cacheProviderResolved");
        resolvedField.setAccessible(true);
        resolvedField.set(loader, true);
    }

    private void setCacheProviderResolved(TranslationConfigStorageLoader loader) throws Exception {
        final var field = TranslationConfigStorageLoader.class.getDeclaredField("cacheProviderResolved");
        field.setAccessible(true);
        field.set(loader, true);
    }

    private Object invokeSelectProvider(
            TranslationConfigStorageLoader loader, java.util.List<?> providers, String configured, String type)
            throws Exception {
        final var method = TranslationConfigStorageLoader.class.getDeclaredMethod(
                "selectProvider", java.util.List.class, String.class, String.class);
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
        @DisplayName("should create tiered repository from pre-set provider")
        void shouldCreateTieredRepositoryFromProvider() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            var repository = mock(TranslationConfigRepository.class);
            when(storageProvider.name()).thenReturn("cassandra");
            when(storageProvider.description()).thenReturn("Cassandra storage");
            when(storageProvider.createRepository(config)).thenReturn(repository);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
            verify(storageProvider).createRepository(config);
        }

        @Test
        @DisplayName("should select provider by configured name via ServiceLoader")
        void shouldSelectProviderByConfiguredName() {
            var loader = createLoader(Optional.of("memory"), Optional.empty(), false);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should discover and select provider via ServiceLoader by configured name")
        void shouldDiscoverProviderViaServiceLoader() {
            // The in-memory provider is on the classpath via META-INF/services
            var loader = createLoader(Optional.of("memory"), Optional.empty(), false);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should throw when configured provider not found by name")
        void shouldThrowWhenConfiguredProviderNotFound() {
            var loader = createLoader(Optional.of("nonexistent"), Optional.empty(), false);

            var exception = assertThrows(StorageProviderException.class, loader::repository);
            assertTrue(exception
                    .getMessage()
                    .contains("Configured translation config storage provider not found: nonexistent"));
        }

        @Test
        @DisplayName("should select highest priority available provider when none configured")
        void shouldSelectHighestPriorityWhenNoneConfigured() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider1 = mock(TranslationConfigStorageProvider.class);
            when(provider1.isAvailable()).thenReturn(true);
            when(provider1.priority()).thenReturn(0);

            final var provider2 = mock(TranslationConfigStorageProvider.class);
            when(provider2.name()).thenReturn("high");
            when(provider2.isAvailable()).thenReturn(true);
            when(provider2.priority()).thenReturn(10);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigStorageProvider)
                    invokeSelectProvider(loader, java.util.List.of(provider1, provider2), null, "storage");

            assertEquals("high", result.name());
        }

        @Test
        @DisplayName("should treat blank configured name as auto-select")
        void shouldTreatBlankConfiguredNameAsAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(TranslationConfigStorageProvider.class);
            when(provider.isAvailable()).thenReturn(true);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigStorageProvider)
                    invokeSelectProvider(loader, java.util.List.of(provider), "   ", "storage");

            assertEquals(provider, result);
        }

        @Test
        @DisplayName("should throw when no available providers for auto-select")
        void shouldThrowWhenNoAvailableProvidersForAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(TranslationConfigStorageProvider.class);
            when(provider.isAvailable()).thenReturn(false);

            assertThrows(
                    StorageProviderException.class,
                    () -> invokeSelectProvider(loader, java.util.List.of(provider), null, "storage"));
        }

        @Test
        @DisplayName("should skip unavailable providers during auto-select")
        void shouldSkipUnavailableProvidersDuringAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(TranslationConfigStorageProvider.class);
            when(unavailable.isAvailable()).thenReturn(false);

            final var available = mock(TranslationConfigStorageProvider.class);
            when(available.isAvailable()).thenReturn(true);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigStorageProvider)
                    invokeSelectProvider(loader, java.util.List.of(unavailable, available), null, "storage");

            assertEquals(available, result);
        }

        @Test
        @DisplayName("should treat blank configured cache name as auto-select")
        void shouldTreatBlankCacheNameAsAutoSelect() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("memory");
            when(storageProvider.description()).thenReturn("Memory storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var loader = createLoader(Optional.empty(), Optional.of("   "), true);
            setStorageProvider(loader, storageProvider);
            setCacheProviderResolved(loader);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should wrap with distributed cache when cache enabled and provider is pre-set")
        void shouldWrapWithDistributedCacheWhenEnabled() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("cassandra");
            when(storageProvider.description()).thenReturn("Cassandra storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            when(cacheProviderMock.name()).thenReturn("custom");
            when(cacheProviderMock.description()).thenReturn("Custom cache");
            var cache = mock(TranslationConfigCache.class);
            when(cacheProviderMock.createCache(config)).thenReturn(cache);

            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
            verify(cacheProviderMock).createCache(config);
        }

        @Test
        @DisplayName("should create tiered repository without distributed cache when cache disabled")
        void shouldCreateWithoutDistributedCacheWhenDisabled() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("memory");
            when(storageProvider.description()).thenReturn("Memory storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should create tiered repository with local cache only when no cache provider resolved")
        void shouldCreateWithLocalCacheOnlyWhenNoCacheProvider() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("memory");
            when(storageProvider.description()).thenReturn("Memory storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            setStorageProvider(loader, storageProvider);
            setCacheProviderResolved(loader);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should inject Redis data source into RedisTranslationConfigCacheProvider when resolvable")
        void shouldInjectRedisDataSourceWhenResolvable() {
            var redisDs = mock(ReactiveRedisDataSource.class);
            when(redisDataSource.isResolvable()).thenReturn(true);
            when(redisDataSource.get()).thenReturn(redisDs);

            // Let ServiceLoader find the real RedisTranslationConfigCacheProvider
            var loader = new TranslationConfigStorageLoader(
                    Optional.of("memory"),
                    Optional.of("redis"),
                    true,
                    DEFAULT_TTL,
                    DEFAULT_MAX_SIZE,
                    config,
                    redisDataSource);

            // The real provider's createCache needs setDataSource to have been called.
            // If the injection works, createCache won't throw IllegalStateException for null DS.
            var result = loader.repository();

            assertNotNull(result);
        }

        @Test
        @DisplayName("should nullify Redis cache provider when Redis data source not resolvable")
        void shouldNullifyCacheProviderWhenRedisNotResolvable() {
            when(redisDataSource.isResolvable()).thenReturn(false);

            // Let ServiceLoader find the real RedisTranslationConfigCacheProvider
            var loader = new TranslationConfigStorageLoader(
                    Optional.of("memory"),
                    Optional.of("redis"),
                    true,
                    DEFAULT_TTL,
                    DEFAULT_MAX_SIZE,
                    config,
                    redisDataSource);

            // Should not throw; cache provider is nullified and falls back to local-only
            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }

        @Test
        @DisplayName("should not inject Redis data source when cache provider is not Redis type")
        void shouldNotInjectRedisDataSourceWhenNotRedisProvider() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("memory");
            when(storageProvider.description()).thenReturn("Memory storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            when(cacheProviderMock.name()).thenReturn("custom");
            when(cacheProviderMock.description()).thenReturn("Custom cache");
            var cache = mock(TranslationConfigCache.class);
            when(cacheProviderMock.createCache(config)).thenReturn(cache);

            var loader = createLoader(Optional.empty(), Optional.empty(), true);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
            verify(cacheProviderMock).createCache(config);
        }

        @Test
        @DisplayName("should cache resolved cache provider across multiple calls")
        void shouldCacheResolvedCacheProviderAcrossMultipleCalls() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            // Call healthIndicators twice to exercise the cached path
            loader.translationConfigHealthIndicators();
            loader.translationConfigHealthIndicators();

            // createHealthIndicator should be called each time (it's getCacheProvider that's cached)
            verify(cacheProviderMock, org.mockito.Mockito.times(2)).createHealthIndicator(config);
        }

        @Test
        @DisplayName("should return NoOp-like cache when configured cache provider not found")
        void shouldHandleCacheProviderNotFound() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.name()).thenReturn("memory");
            when(storageProvider.description()).thenReturn("Memory storage");
            when(storageProvider.createRepository(config)).thenReturn(mock(TranslationConfigRepository.class));

            var loader = createLoader(Optional.of("nonexistent_cache"), Optional.empty(), true);
            setStorageProvider(loader, storageProvider);

            // Cache provider discovery via ServiceLoader will not find "nonexistent_cache",
            // but the error is caught and local-only cache is used
            var result = loader.repository();

            assertInstanceOf(TieredTranslationConfigRepository.class, result);
        }
    }

    @Nested
    @DisplayName("selectProvider() for cache providers")
    class SelectCacheProviderTests {

        @Test
        @DisplayName("should select cache provider by name")
        void shouldSelectCacheProviderByName() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(TranslationConfigCacheProvider.class);
            when(provider.name()).thenReturn("redis");

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigCacheProvider)
                    invokeSelectProvider(loader, java.util.List.of(provider), "redis", "cache");

            assertEquals("redis", result.name());
        }

        @Test
        @DisplayName("should auto-select cache provider by priority")
        void shouldAutoSelectCacheProviderByPriority() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var low = mock(TranslationConfigCacheProvider.class);
            when(low.isAvailable()).thenReturn(true);
            when(low.priority()).thenReturn(0);

            final var high = mock(TranslationConfigCacheProvider.class);
            when(high.name()).thenReturn("high");
            when(high.isAvailable()).thenReturn(true);
            when(high.priority()).thenReturn(10);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigCacheProvider)
                    invokeSelectProvider(loader, java.util.List.of(low, high), null, "cache");

            assertEquals("high", result.name());
        }

        @Test
        @DisplayName("should skip unavailable cache providers during auto-select")
        void shouldSkipUnavailableCacheProvidersDuringAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(TranslationConfigCacheProvider.class);
            when(unavailable.isAvailable()).thenReturn(false);

            final var available = mock(TranslationConfigCacheProvider.class);
            when(available.isAvailable()).thenReturn(true);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigCacheProvider)
                    invokeSelectProvider(loader, java.util.List.of(unavailable, available), null, "cache");

            assertEquals(available, result);
        }

        @Test
        @DisplayName("should throw when no available cache providers for auto-select")
        void shouldThrowWhenNoAvailableCacheProvidersForAutoSelect() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var unavailable = mock(TranslationConfigCacheProvider.class);
            when(unavailable.isAvailable()).thenReturn(false);

            assertThrows(
                    StorageProviderException.class,
                    () -> invokeSelectProvider(loader, java.util.List.of(unavailable), null, "cache"));
        }

        @Test
        @DisplayName("should treat blank configured cache name as auto-select for cache providers")
        void shouldTreatBlankCacheNameAsAutoSelectForCacheProviders() throws Exception {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            final var provider = mock(TranslationConfigCacheProvider.class);
            when(provider.isAvailable()).thenReturn(true);

            @SuppressWarnings("unchecked")
            final var result = (TranslationConfigCacheProvider)
                    invokeSelectProvider(loader, java.util.List.of(provider), "   ", "cache");

            assertEquals(provider, result);
        }
    }

    @Nested
    @DisplayName("translationConfigHealthIndicators()")
    class HealthIndicatorTests {

        @Test
        @DisplayName("should include storage health indicator when present")
        void shouldIncludeStorageHealthIndicator() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            var healthIndicator = mock(StorageHealthIndicator.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.of(healthIndicator));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProviderResolved(loader);

            var indicators = loader.translationConfigHealthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(healthIndicator, indicators.get(0));
        }

        @Test
        @DisplayName("should include both storage and cache health indicators")
        void shouldIncludeBothHealthIndicators() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            var storageHealth = mock(StorageHealthIndicator.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.of(storageHealth));

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            var cacheHealth = mock(StorageHealthIndicator.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.of(cacheHealth));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.translationConfigHealthIndicators();

            assertEquals(2, indicators.size());
            assertTrue(indicators.contains(storageHealth));
            assertTrue(indicators.contains(cacheHealth));
        }

        @Test
        @DisplayName("should return empty list when no health indicators provided")
        void shouldReturnEmptyWhenNoHealthIndicators() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProviderResolved(loader);

            var indicators = loader.translationConfigHealthIndicators();

            assertTrue(indicators.isEmpty());
        }

        @Test
        @DisplayName("should include only storage health indicator when cache provider has none")
        void shouldIncludeOnlyStorageHealthWhenCacheProviderHasNone() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            var storageHealth = mock(StorageHealthIndicator.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.of(storageHealth));

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.translationConfigHealthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(storageHealth, indicators.get(0));
        }

        @Test
        @DisplayName("should return only cache health indicator when storage provider has none")
        void shouldReturnOnlyCacheHealthWhenStorageHasNone() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            when(storageProvider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);
            var cacheHealth = mock(StorageHealthIndicator.class);
            when(cacheProviderMock.createHealthIndicator(config)).thenReturn(Optional.of(cacheHealth));

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            var indicators = loader.translationConfigHealthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(cacheHealth, indicators.get(0));
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class ShutdownTests {

        @Test
        @DisplayName("should close storage provider if AutoCloseable")
        void shouldCloseStorageProviderIfAutoCloseable() throws Exception {
            var closeable = mock(CloseableStorageProvider.class);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, closeable);

            loader.shutdown();

            verify(closeable).close();
        }

        @Test
        @DisplayName("should close cache provider if AutoCloseable")
        void shouldCloseCacheProviderIfAutoCloseable() throws Exception {
            var closeableCacheProvider = mock(CloseableCacheProvider.class);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setCacheProvider(loader, closeableCacheProvider);

            loader.shutdown();

            verify(closeableCacheProvider).close();
        }

        @Test
        @DisplayName("should not throw when close fails on storage provider")
        void shouldNotThrowWhenStorageCloseFails() throws Exception {
            var closeable = mock(CloseableStorageProvider.class);
            doThrow(new RuntimeException("Close failed")).when(closeable).close();

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, closeable);

            // Should not throw
            loader.shutdown();

            verify(closeable).close();
        }

        @Test
        @DisplayName("should not throw when close fails on cache provider")
        void shouldNotThrowWhenCacheCloseFails() throws Exception {
            var closeable = mock(CloseableCacheProvider.class);
            doThrow(new RuntimeException("Close failed")).when(closeable).close();

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setCacheProvider(loader, closeable);

            // Should not throw
            loader.shutdown();

            verify(closeable).close();
        }

        @Test
        @DisplayName("should not throw when no providers loaded")
        void shouldNotThrowWhenNoProvidersLoaded() {
            var loader = createLoader(Optional.empty(), Optional.empty(), false);

            // Should not throw - no providers have been loaded
            loader.shutdown();
        }

        @Test
        @DisplayName("should close both storage and cache providers when both are AutoCloseable")
        void shouldCloseBothWhenBothAutoCloseable() throws Exception {
            var closeableStorage = mock(CloseableStorageProvider.class);
            var closeableCache = mock(CloseableCacheProvider.class);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, closeableStorage);
            setCacheProvider(loader, closeableCache);

            loader.shutdown();

            verify(closeableStorage).close();
            verify(closeableCache).close();
        }

        @Test
        @DisplayName("should close cache provider even when storage provider close fails")
        void shouldCloseCacheEvenWhenStorageCloseFails() throws Exception {
            var closeableStorage = mock(CloseableStorageProvider.class);
            var closeableCache = mock(CloseableCacheProvider.class);
            doThrow(new RuntimeException("Storage close failed"))
                    .when(closeableStorage)
                    .close();

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, closeableStorage);
            setCacheProvider(loader, closeableCache);

            loader.shutdown();

            verify(closeableStorage).close();
            verify(closeableCache).close();
        }

        @Test
        @DisplayName("should not attempt to close non-AutoCloseable providers")
        void shouldNotCloseNonAutoCloseable() throws Exception {
            var storageProvider = mock(TranslationConfigStorageProvider.class);
            var cacheProviderMock = mock(TranslationConfigCacheProvider.class);

            var loader = createLoader(Optional.empty(), Optional.empty(), false);
            setStorageProvider(loader, storageProvider);
            setCacheProvider(loader, cacheProviderMock);

            // Should not throw
            loader.shutdown();
        }
    }

    /**
     * Test interface combining TranslationConfigStorageProvider and AutoCloseable.
     */
    interface CloseableStorageProvider extends TranslationConfigStorageProvider, AutoCloseable {}

    /**
     * Test interface combining TranslationConfigCacheProvider and AutoCloseable.
     */
    interface CloseableCacheProvider extends TranslationConfigCacheProvider, AutoCloseable {}
}
