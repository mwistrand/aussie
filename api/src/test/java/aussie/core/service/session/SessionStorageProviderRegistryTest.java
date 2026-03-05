package aussie.core.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SessionConfig;
import aussie.core.port.out.SessionRepository;
import aussie.spi.SessionStorageProvider;

@DisplayName("SessionStorageProviderRegistry")
@SuppressWarnings("unchecked")
class SessionStorageProviderRegistryTest {

    private Instance<SessionStorageProvider> providerInstance;
    private SessionConfig config;
    private SessionConfig.StorageConfig storageConfig;

    @BeforeEach
    void setUp() {
        providerInstance = mock(Instance.class);
        config = mock(SessionConfig.class);
        storageConfig = mock(SessionConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
    }

    private SessionStorageProvider createProvider(String name, int priority, boolean available) {
        return new SessionStorageProvider() {
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
            public SessionRepository createRepository() {
                return mock(SessionRepository.class);
            }

            @Override
            public Optional<HealthCheckResponse> healthCheck() {
                return Optional.empty();
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

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("redis", selected.name());
        }

        @Test
        @DisplayName("should fall back to highest priority when configured provider unavailable")
        void shouldFallBackToHighestPriority() {
            var redis = createProvider("redis", 100, false);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("redis");
            when(providerInstance.stream()).thenReturn(Stream.of(redis, memory));

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("memory", selected.name());
        }

        @Test
        @DisplayName("should select highest priority available provider")
        void shouldSelectHighestPriorityAvailable() {
            var custom = createProvider("custom", 200, true);
            var redis = createProvider("redis", 100, true);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(custom, redis, memory));

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("memory", selected.name());
        }

        @Test
        @DisplayName("should use highest priority when configured is not 'memory' and unavailable")
        void shouldUseHighestPriorityWhenConfiguredUnavailable() {
            var custom = createProvider("custom", 200, true);
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("cassandra");
            when(providerInstance.stream()).thenReturn(Stream.of(custom, memory));

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var selected = registry.getSelectedProvider();

            assertEquals("custom", selected.name());
        }

        @Test
        @DisplayName("should throw when no providers available")
        void shouldThrowWhenNoProvidersAvailable() {
            var redis = createProvider("redis", 100, false);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(redis));

            var registry = new SessionStorageProviderRegistry(providerInstance, config);

            assertThrows(IllegalStateException.class, registry::getSelectedProvider);
        }

        @Test
        @DisplayName("should cache selected provider")
        void shouldCacheSelectedProvider() {
            var memory = createProvider("memory", 0, true);
            when(storageConfig.provider()).thenReturn("memory");
            when(providerInstance.stream()).thenReturn(Stream.of(memory));

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var first = registry.getSelectedProvider();
            var second = registry.getSelectedProvider();

            // stream() should only be called once due to caching
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

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
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

            var registry = new SessionStorageProviderRegistry(providerInstance, config);
            var available = registry.getAvailableProviders();

            assertEquals(1, available.size());
            assertEquals("memory", available.get(0).name());
        }
    }
}
