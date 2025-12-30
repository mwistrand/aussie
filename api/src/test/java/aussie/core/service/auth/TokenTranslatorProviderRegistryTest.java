package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.TranslatedClaims;
import aussie.spi.TokenTranslatorProvider;

/**
 * Unit tests for TokenTranslatorProviderRegistry.
 */
@DisplayName("TokenTranslatorProviderRegistry")
@ExtendWith(MockitoExtension.class)
class TokenTranslatorProviderRegistryTest {

    @Mock
    private Instance<TokenTranslatorProvider> providers;

    @Mock
    private TokenTranslationConfig config;

    private TokenTranslatorProviderRegistry registry;

    @Nested
    @DisplayName("Provider selection")
    class ProviderSelection {

        @Test
        @DisplayName("should select configured provider when available")
        void shouldSelectConfiguredProvider() {
            var customProvider = createProvider("custom", 50, true);
            var defaultProvider = createProvider("default", 100, true);

            when(config.provider()).thenReturn("custom");
            when(providers.stream()).thenReturn(Stream.of(customProvider, defaultProvider));

            registry = new TokenTranslatorProviderRegistry(providers, config);

            var selected = registry.getProvider();

            assertEquals("custom", selected.name());
        }

        @Test
        @DisplayName("should select highest priority when configured provider not available")
        void shouldSelectHighestPriorityWhenConfiguredNotAvailable() {
            var lowPriority = createProvider("low", 50, true);
            var highPriority = createProvider("high", 100, true);

            when(config.provider()).thenReturn("missing");
            when(providers.stream()).thenReturn(Stream.of(lowPriority, highPriority));

            registry = new TokenTranslatorProviderRegistry(providers, config);

            var selected = registry.getProvider();

            assertEquals("high", selected.name());
        }

        @Test
        @DisplayName("should exclude unavailable providers")
        void shouldExcludeUnavailableProviders() {
            var unavailable = createProvider("unavailable", 100, false);
            var available = createProvider("available", 50, true);

            when(config.provider()).thenReturn("default");
            when(providers.stream()).thenReturn(Stream.of(unavailable, available));

            registry = new TokenTranslatorProviderRegistry(providers, config);

            var selected = registry.getProvider();

            assertEquals("available", selected.name());
        }

        @Test
        @DisplayName("should throw when no providers available")
        void shouldThrowWhenNoProvidersAvailable() {
            when(config.provider()).thenReturn("default");
            when(providers.stream()).thenReturn(Stream.empty());

            registry = new TokenTranslatorProviderRegistry(providers, config);

            assertThrows(IllegalStateException.class, () -> registry.getProvider());
        }

        @Test
        @DisplayName("should cache selected provider")
        void shouldCacheSelectedProvider() {
            var provider = createProvider("cached", 100, true);

            when(config.provider()).thenReturn("default");
            when(providers.stream()).thenReturn(Stream.of(provider));

            registry = new TokenTranslatorProviderRegistry(providers, config);

            var first = registry.getProvider();
            var second = registry.getProvider();

            assertEquals(first, second);
        }
    }

    @Nested
    @DisplayName("Available providers")
    class AvailableProviders {

        @Test
        @DisplayName("should return only available providers")
        void shouldReturnOnlyAvailableProviders() {
            var available1 = createProvider("available1", 50, true);
            var unavailable = createProvider("unavailable", 100, false);
            var available2 = createProvider("available2", 75, true);

            when(providers.stream()).thenReturn(Stream.of(available1, unavailable, available2));

            registry = new TokenTranslatorProviderRegistry(providers, config);

            List<TokenTranslatorProvider> result = registry.getAvailableProviders();

            assertEquals(2, result.size());
            assertTrue(result.stream().noneMatch(p -> p.name().equals("unavailable")));
        }

        @Test
        @DisplayName("should return empty list when no providers available")
        void shouldReturnEmptyListWhenNoProvidersAvailable() {
            when(providers.stream()).thenReturn(Stream.empty());

            registry = new TokenTranslatorProviderRegistry(providers, config);

            List<TokenTranslatorProvider> result = registry.getAvailableProviders();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    private TokenTranslatorProvider createProvider(String name, int priority, boolean available) {
        return new TokenTranslatorProvider() {
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
            public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
                return Uni.createFrom().item(TranslatedClaims.empty());
            }
        };
    }
}
