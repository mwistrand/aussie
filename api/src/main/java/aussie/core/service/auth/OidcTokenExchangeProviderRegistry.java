package aussie.core.service.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.config.OidcConfig;
import aussie.spi.OidcTokenExchangeProvider;

/**
 * Registry for OIDC token exchange providers.
 *
 * <p>Discovers available providers via CDI and selects the appropriate one
 * based on configuration and availability.
 *
 * <p>Selection order:
 * <ol>
 *   <li>Configured provider (aussie.auth.oidc.token-exchange.provider)</li>
 *   <li>Highest priority available provider</li>
 * </ol>
 */
@ApplicationScoped
public class OidcTokenExchangeProviderRegistry {

    private static final Logger LOG = Logger.getLogger(OidcTokenExchangeProviderRegistry.class);

    private final Instance<OidcTokenExchangeProvider> providers;
    private final OidcConfig config;

    private volatile OidcTokenExchangeProvider selectedProvider;

    @Inject
    public OidcTokenExchangeProviderRegistry(Instance<OidcTokenExchangeProvider> providers, OidcConfig config) {
        this.providers = providers;
        this.config = config;
    }

    /**
     * Get the selected token exchange provider.
     *
     * @return Selected provider
     * @throws IllegalStateException if no providers are available
     */
    public synchronized OidcTokenExchangeProvider getProvider() {
        if (selectedProvider == null) {
            selectedProvider = selectProvider();
        }
        return selectedProvider;
    }

    private OidcTokenExchangeProvider selectProvider() {
        final var configuredProvider = config.tokenExchange().provider();
        final var availableProviders = providers.stream()
                .filter(OidcTokenExchangeProvider::isAvailable)
                .sorted(Comparator.comparingInt(OidcTokenExchangeProvider::priority)
                        .reversed())
                .toList();

        LOG.debugf(
                "Available OIDC token exchange providers: %s",
                availableProviders.stream().map(OidcTokenExchangeProvider::name).toList());

        // First, try to find the configured provider
        Optional<OidcTokenExchangeProvider> configured = availableProviders.stream()
                .filter(p -> p.name().equals(configuredProvider))
                .findFirst();

        if (configured.isPresent()) {
            LOG.infof("Using configured OIDC token exchange provider: %s", configuredProvider);
            return configured.get();
        }

        // If configured provider not available, log warning and use highest priority
        if (!configuredProvider.equals("default")) {
            LOG.warnf(
                    "Configured OIDC token exchange provider '%s' is not available, falling back", configuredProvider);
        }

        // Use highest priority available provider
        if (!availableProviders.isEmpty()) {
            final var provider = availableProviders.get(0);
            LOG.infof("Using OIDC token exchange provider: %s (priority: %d)", provider.name(), provider.priority());
            return provider;
        }

        throw new IllegalStateException("No OIDC token exchange providers available");
    }

    /**
     * Get all available providers (for health checks and diagnostics).
     *
     * @return List of available providers
     */
    public List<OidcTokenExchangeProvider> getAvailableProviders() {
        return providers.stream().filter(OidcTokenExchangeProvider::isAvailable).toList();
    }
}
