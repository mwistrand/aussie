package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.port.out.RoleRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.RoleStorageProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

@DisplayName("RoleStorageProviderLoader")
@ExtendWith(MockitoExtension.class)
class RoleStorageProviderLoaderTest {

    @Mock
    private StorageAdapterConfig config;

    private void setStorageProvider(RoleStorageProviderLoader loader, RoleStorageProvider provider) throws Exception {
        final var field = RoleStorageProviderLoader.class.getDeclaredField("storageProvider");
        field.setAccessible(true);
        field.set(loader, provider);
    }

    @Nested
    @DisplayName("roleRepository()")
    class RoleRepositoryTests {

        @Test
        @DisplayName("should create repository from pre-set provider")
        void shouldCreateRepositoryFromProvider() throws Exception {
            var provider = mock(RoleStorageProvider.class);
            var repository = mock(RoleRepository.class);
            when(provider.name()).thenReturn("cassandra");
            when(provider.description()).thenReturn("Cassandra role storage");
            when(provider.createRepository(config)).thenReturn(repository);

            var loader = new RoleStorageProviderLoader(Optional.empty(), config);
            setStorageProvider(loader, provider);

            var result = loader.roleRepository();

            assertEquals(repository, result);
            verify(provider).createRepository(config);
        }

        @Test
        @DisplayName("should select provider by configured name via ServiceLoader")
        void shouldSelectProviderByConfiguredName() {
            var loader = new RoleStorageProviderLoader(Optional.of("memory"), config);

            var result = loader.roleRepository();

            assertNotNull(result);
        }

        @Test
        @DisplayName("should select highest priority available provider when none configured")
        void shouldSelectHighestPriorityWhenNoneConfigured() {
            var loader = new RoleStorageProviderLoader(Optional.empty(), config);

            var result = loader.roleRepository();

            assertNotNull(result);
        }

        @Test
        @DisplayName("should throw when configured provider not found by name")
        void shouldThrowWhenConfiguredProviderNotFound() {
            var loader = new RoleStorageProviderLoader(Optional.of("nonexistent"), config);

            var exception = assertThrows(StorageProviderException.class, loader::roleRepository);
            assertTrue(exception.getMessage().contains("Configured role storage provider not found: nonexistent"));
        }

        @Test
        @DisplayName("should cache provider after first resolution")
        void shouldCacheProviderAfterFirstResolution() throws Exception {
            var provider = mock(RoleStorageProvider.class);
            var repository = mock(RoleRepository.class);
            when(provider.name()).thenReturn("memory");
            when(provider.description()).thenReturn("In-memory role storage");
            when(provider.createRepository(config)).thenReturn(repository);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = new RoleStorageProviderLoader(Optional.empty(), config);
            setStorageProvider(loader, provider);

            // Both calls should use the cached provider
            loader.roleRepository();
            loader.roleHealthIndicators();

            // createRepository is called by roleRepository
            verify(provider).createRepository(config);
            // createHealthIndicator is called by roleHealthIndicators
            verify(provider).createHealthIndicator(config);
        }
    }

    @Nested
    @DisplayName("roleHealthIndicators()")
    class HealthIndicatorTests {

        @Test
        @DisplayName("should include health indicator when present")
        void shouldIncludeHealthIndicatorWhenPresent() throws Exception {
            var provider = mock(RoleStorageProvider.class);
            var healthIndicator = mock(StorageHealthIndicator.class);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.of(healthIndicator));

            var loader = new RoleStorageProviderLoader(Optional.empty(), config);
            setStorageProvider(loader, provider);

            var indicators = loader.roleHealthIndicators();

            assertEquals(1, indicators.size());
            assertEquals(healthIndicator, indicators.get(0));
        }

        @Test
        @DisplayName("should return empty list when no health indicator provided")
        void shouldReturnEmptyWhenNoHealthIndicator() throws Exception {
            var provider = mock(RoleStorageProvider.class);
            when(provider.createHealthIndicator(config)).thenReturn(Optional.empty());

            var loader = new RoleStorageProviderLoader(Optional.empty(), config);
            setStorageProvider(loader, provider);

            var indicators = loader.roleHealthIndicators();

            assertTrue(indicators.isEmpty());
        }
    }
}
