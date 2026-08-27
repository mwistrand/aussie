package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;
import aussie.spi.PkceStorageProvider;

@DisplayName("PkceStorageProviderRegistry")
@SuppressWarnings("unchecked")
class PkceStorageProviderRegistryTest {

    private Instance<PkceStorageProvider> providerInstance;
    private PkceConfig config;
    private PkceConfig.StorageConfig storageConfig;

    @BeforeEach
    void setUp() {
        providerInstance = mock(Instance.class);
        config = mock(PkceConfig.class);
        storageConfig = mock(PkceConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
    }

    private PkceStorageProvider createProvider(String name, int priority, boolean available) {
        return new PkceStorageProvider() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public PkceChallengeRepository createRepository() {
                return mock(PkceChallengeRepository.class);
            }
        };
    }

    @Nested
    @DisplayName("getSelectedProvider()")
    class GetSelectedProviderTests {

        @Test
        @DisplayName("should select configured provider when available")
        void shouldSelectConfiguredProvider() {
            var redis = createProvider("redis", 100, true);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("redis");
            when(providerInstance.stream()).thenReturn(Stream.of(redis, memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("redis", selected.name());
        }

        @Test
        @DisplayName("should retain configured provider when unavailable")
        void shouldRetainConfiguredProviderWhenUnavailable() {
            var redis = createProvider("redis", 100, false);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("redis");
            when(providerInstance.stream()).thenReturn(Stream.of(redis, memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("redis", selected.name());
        }

        @Test
        @DisplayName("should select highest priority available provider")
        void shouldSelectHighestPriorityAvailable() {
            var custom = createProvider("custom", 200, true);
            var redis = createProvider("redis", 100, true);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("");
            when(providerInstance.stream()).thenReturn(Stream.of(custom, redis, memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("custom", selected.name());
        }

        @Test
        @DisplayName("should select explicitly configured memory provider")
        void shouldSelectExplicitlyConfiguredMemoryProvider() {
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("memory", selected.name());
        }

        @Test
        @DisplayName("should throw when no providers available")
        void shouldThrowWhenNoProvidersAvailable() {
            var redis = createProvider("redis", 100, false);
            when(storageConfig.provider()).thenReturn("");
            when(providerInstance.stream()).thenReturn(Stream.of(redis));

            final var registry = new PkceStorageProviderRegistry(providerInstance, config);

            assertThrows(IllegalStateException.class, registry::getSelectedProvider);
        }

        @Test
        @DisplayName("should reject an explicitly configured provider that is not installed")
        void shouldRejectMissingConfiguredProvider() {
            when(storageConfig.provider()).thenReturn("redis");
            when(providerInstance.stream()).thenReturn(Stream.of(createProvider("memory", 0, true)));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);

            assertThrows(IllegalStateException.class, registry::getSelectedProvider);
        }

        @Test
        @DisplayName("should cache selected provider")
        void shouldCacheSelectedProvider() {
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var first = registry.getSelectedProvider();
            var second = registry.getSelectedProvider();

            assertEquals(first, second);
        }
    }

    @Nested
    @DisplayName("getRepository()")
    class GetRepositoryTests {

        @Test
        @DisplayName("should return repository from selected provider")
        void shouldReturnRepositoryFromSelectedProvider() {
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var repo = registry.getRepository();

            assertNotNull(repo);
        }
    }

    @Nested
    @DisplayName("getAvailableProviders()")
    class GetAvailableProvidersTests {

        @Test
        @DisplayName("should return only available providers")
        void shouldReturnOnlyAvailableProviders() {
            var redis = createProvider("redis", 100, false);
            var memory = createProvider("memory", 0, true);
            when(providerInstance.stream()).thenReturn(Stream.of(redis, memory));

            var registry = new PkceStorageProviderRegistry(providerInstance, config);
            var available = registry.getAvailableProviders();

            assertEquals(1, available.size());
            assertEquals("memory", available.get(0).name());
        }
    }
}
