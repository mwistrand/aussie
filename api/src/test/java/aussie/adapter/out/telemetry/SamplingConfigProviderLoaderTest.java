package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.port.out.SamplingConfigRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.SamplingConfigProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

@DisplayName("SamplingConfigProviderLoader")
@ExtendWith(MockitoExtension.class)
class SamplingConfigProviderLoaderTest {

    @Mock
    private StorageAdapterConfig config;

    @SuppressWarnings("unchecked")
    private static ServiceLoader<SamplingConfigProvider> mockServiceLoader(List<SamplingConfigProvider> providers) {
        var serviceLoader = mock(ServiceLoader.class);
        doAnswer(invocation -> {
                    Consumer<SamplingConfigProvider> consumer = invocation.getArgument(0);
                    providers.forEach(consumer);
                    return null;
                })
                .when(serviceLoader)
                .forEach(any());
        return serviceLoader;
    }

    @Nested
    @DisplayName("repository()")
    class RepositoryTests {

        @Test
        @DisplayName("should create repository from configured provider by name")
        void shouldCreateRepositoryFromConfiguredProvider() {
            var provider = mock(SamplingConfigProvider.class);
            var repository = mock(SamplingConfigRepository.class);
            when(provider.name()).thenReturn("cassandra");
            when(provider.description()).thenReturn("Cassandra sampling config");
            when(provider.createRepository(config)).thenReturn(repository);

            var serviceLoader = mockServiceLoader(List.of(provider));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.of("cassandra"), config);

                var result = loader.repository();

                assertEquals(repository, result);
                verify(provider).createRepository(config);
            }
        }

        @Test
        @DisplayName("should select highest priority available provider when none configured")
        void shouldSelectHighestPriorityWhenNoneConfigured() {
            var lowPriority = mock(SamplingConfigProvider.class);
            when(lowPriority.name()).thenReturn("memory");
            when(lowPriority.priority()).thenReturn(0);
            when(lowPriority.isAvailable()).thenReturn(true);

            var highPriority = mock(SamplingConfigProvider.class);
            when(highPriority.name()).thenReturn("cassandra");
            when(highPriority.description()).thenReturn("Cassandra sampling config");
            when(highPriority.priority()).thenReturn(10);
            when(highPriority.isAvailable()).thenReturn(true);

            var repository = mock(SamplingConfigRepository.class);
            when(highPriority.createRepository(config)).thenReturn(repository);

            var serviceLoader = mockServiceLoader(List.of(lowPriority, highPriority));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var result = loader.repository();

                assertEquals(repository, result);
                verify(highPriority).createRepository(config);
            }
        }

        @Test
        @DisplayName("should throw when no providers found")
        void shouldThrowWhenNoProvidersFound() {
            var serviceLoader = mockServiceLoader(List.of());

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var exception = assertThrows(StorageProviderException.class, loader::repository);
                assertTrue(exception.getMessage().contains("No sampling config providers found"));
            }
        }

        @Test
        @DisplayName("should throw when configured provider not found by name")
        void shouldThrowWhenConfiguredProviderNotFound() {
            var provider = mock(SamplingConfigProvider.class);
            when(provider.name()).thenReturn("memory");

            var serviceLoader = mockServiceLoader(List.of(provider));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.of("nonexistent"), config);

                var exception = assertThrows(StorageProviderException.class, loader::repository);
                assertTrue(
                        exception.getMessage().contains("Configured sampling config provider not found: nonexistent"));
            }
        }

        @Test
        @DisplayName("should throw when no available providers and none configured")
        void shouldThrowWhenNoAvailableProviders() {
            var unavailable = mock(SamplingConfigProvider.class);
            when(unavailable.name()).thenReturn("cassandra");
            when(unavailable.isAvailable()).thenReturn(false);

            var serviceLoader = mockServiceLoader(List.of(unavailable));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var exception = assertThrows(StorageProviderException.class, loader::repository);
                assertTrue(exception.getMessage().contains("No available sampling config providers"));
            }
        }

        @Test
        @DisplayName("should skip unavailable providers during priority selection")
        void shouldSkipUnavailableProvidersDuringPrioritySelection() {
            var unavailableHigh = mock(SamplingConfigProvider.class);
            when(unavailableHigh.name()).thenReturn("cassandra");
            when(unavailableHigh.isAvailable()).thenReturn(false);

            var availableLow = mock(SamplingConfigProvider.class);
            when(availableLow.name()).thenReturn("memory");
            when(availableLow.description()).thenReturn("In-memory sampling config");
            when(availableLow.isAvailable()).thenReturn(true);

            var repository = mock(SamplingConfigRepository.class);
            when(availableLow.createRepository(config)).thenReturn(repository);

            var serviceLoader = mockServiceLoader(List.of(unavailableHigh, availableLow));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var result = loader.repository();

                assertEquals(repository, result);
                verify(availableLow).createRepository(config);
            }
        }

        @Test
        @DisplayName("should cache provider on subsequent calls")
        void shouldCacheProviderOnSubsequentCalls() {
            var provider = mock(SamplingConfigProvider.class);
            when(provider.name()).thenReturn("memory");
            when(provider.description()).thenReturn("In-memory sampling config");
            when(provider.isAvailable()).thenReturn(true);

            var repository = mock(SamplingConfigRepository.class);
            when(provider.createRepository(config)).thenReturn(repository);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var serviceLoader = mockServiceLoader(List.of(provider));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var result1 = loader.repository();
                var indicators = loader.samplingHealthIndicators();

                assertNotNull(result1);
                assertNotNull(indicators);
            }
        }
    }

    @Nested
    @DisplayName("samplingHealthIndicators()")
    class SamplingHealthIndicatorsTests {

        @Test
        @DisplayName("should include health indicator when provided")
        void shouldIncludeHealthIndicatorWhenProvided() {
            var provider = mock(SamplingConfigProvider.class);
            var healthIndicator = mock(StorageHealthIndicator.class);
            when(provider.name()).thenReturn("cassandra");
            when(provider.isAvailable()).thenReturn(true);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.of(healthIndicator));

            var serviceLoader = mockServiceLoader(List.of(provider));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var indicators = loader.samplingHealthIndicators();

                assertEquals(1, indicators.size());
                assertEquals(healthIndicator, indicators.get(0));
            }
        }

        @Test
        @DisplayName("should return empty list when no health indicator provided")
        void shouldReturnEmptyListWhenNoHealthIndicator() {
            var provider = mock(SamplingConfigProvider.class);
            when(provider.name()).thenReturn("memory");
            when(provider.isAvailable()).thenReturn(true);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var serviceLoader = mockServiceLoader(List.of(provider));

            try (MockedStatic<ServiceLoader> sl = mockStatic(ServiceLoader.class)) {
                sl.when(() -> ServiceLoader.load(SamplingConfigProvider.class)).thenReturn(serviceLoader);

                var loader = new SamplingConfigProviderLoader(Optional.empty(), config);

                var indicators = loader.samplingHealthIndicators();

                assertTrue(indicators.isEmpty());
            }
        }
    }
}
