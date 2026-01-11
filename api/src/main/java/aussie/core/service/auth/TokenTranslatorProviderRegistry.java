package aussie.core.service.auth;

import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.config.TokenTranslationConfig;
import aussie.spi.TokenTranslatorProvider;

/**
 * Registry for token translator providers.
 *
 * <p>Discovers available providers via CDI and selects the appropriate one
 * based on configuration and availability.
 *
 * <p>Selection order:
 * <ol>
 *   <li>Configured provider (aussie.auth.token-translation.provider)</li>
 *   <li>Highest priority available provider</li>
 * </ol>
 */
@ApplicationScoped
public class TokenTranslatorProviderRegistry {

    private static final Logger LOG = Logger.getLogger(TokenTranslatorProviderRegistry.class);

    private final Instance<TokenTranslatorProvider> providers;
    private final TokenTranslationConfig config;

    private TokenTranslatorProvider selectedProvider;

    @Inject
    public TokenTranslatorProviderRegistry(Instance<TokenTranslatorProvider> providers, TokenTranslationConfig config) {
        this.providers = providers;
        this.config = config;
    }

    /**
     * Get the selected token translator provider.
     *
     * @return Selected provider
     * @throws IllegalStateException if no providers are available
     */
    public synchronized TokenTranslatorProvider getProvider() {
        if (selectedProvider == null) {
            selectedProvider = selectProvider();
        }
        return selectedProvider;
    }

    private TokenTranslatorProvider selectProvider() {
        final var configuredProvider = config.provider();
        final var availableProviders = providers.stream()
                .filter(TokenTranslatorProvider::isAvailable)
                .sorted(Comparator.comparingInt(TokenTranslatorProvider::priority)
                        .reversed())
                .toList();

        LOG.debugf(
                "Available token translator providers: %s",
                availableProviders.stream().map(TokenTranslatorProvider::name).toList());

        // First, try to find the configured provider
        final var configured = availableProviders.stream()
                .filter(p -> p.name().equals(configuredProvider))
                .findFirst();

        if (configured.isPresent()) {
            LOG.infof("Using configured token translator provider: %s", configuredProvider);
            return configured.get();
        }

        // If configured provider not available, log warning and use highest priority
        if (!configuredProvider.equals("default")) {
            LOG.warnf("Configured token translator provider '%s' is not available, falling back", configuredProvider);
        }

        // Use highest priority available provider
        if (!availableProviders.isEmpty()) {
            final var provider = availableProviders.get(0);
            LOG.infof("Using token translator provider: %s (priority: %d)", provider.name(), provider.priority());
            return provider;
        }

        throw new IllegalStateException("No token translator providers available");
    }

    /**
     * Get all available providers (for health checks and diagnostics).
     *
     * @return List of available providers
     */
    public List<TokenTranslatorProvider> getAvailableProviders() {
        return providers.stream().filter(TokenTranslatorProvider::isAvailable).toList();
    }
}
