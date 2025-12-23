package aussie.core.service.auth;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;
import aussie.spi.PkceStorageProvider;

/**
 * Registry for PKCE storage providers.
 *
 * <p>Discovers available providers via CDI and selects the appropriate one
 * based on configuration and availability.
 *
 * <p>Selection order:
 * <ol>
 *   <li>Configured provider (aussie.auth.pkce.storage.provider)</li>
 *   <li>Highest priority available provider</li>
 *   <li>Memory fallback (always available)</li>
 * </ol>
 */
@ApplicationScoped
public class PkceStorageProviderRegistry {

    private static final Logger LOG = Logger.getLogger(PkceStorageProviderRegistry.class);

    private final Instance<PkceStorageProvider> providers;
    private final PkceConfig config;

    private volatile PkceStorageProvider selectedProvider;
    private volatile PkceChallengeRepository repository;

    @Inject
    public PkceStorageProviderRegistry(Instance<PkceStorageProvider> providers, PkceConfig config) {
        this.providers = providers;
        this.config = config;
    }

    /**
     * Get the PKCE challenge repository from the selected provider.
     *
     * @return PKCE challenge repository instance
     */
    public synchronized PkceChallengeRepository getRepository() {
        if (repository == null) {
            repository = getSelectedProvider().createRepository();
        }
        return repository;
    }

    /**
     * Get the selected storage provider.
     *
     * @return Selected provider
     */
    public synchronized PkceStorageProvider getSelectedProvider() {
        if (selectedProvider == null) {
            selectedProvider = selectProvider();
        }
        return selectedProvider;
    }

    private PkceStorageProvider selectProvider() {
        final var configuredProvider = config.storage().provider();
        final var availableProviders = providers.stream()
                .filter(PkceStorageProvider::isAvailable)
                .sorted(Comparator.comparingInt(PkceStorageProvider::priority).reversed())
                .toList();

        LOG.debugf(
                "Available PKCE storage providers: %s",
                availableProviders.stream().map(PkceStorageProvider::name).toList());

        // First, try to find the configured provider
        Optional<PkceStorageProvider> configured = availableProviders.stream()
                .filter(p -> p.name().equals(configuredProvider))
                .findFirst();

        if (configured.isPresent()) {
            LOG.infof("Using configured PKCE storage provider: %s", configuredProvider);
            return configured.get();
        }

        // If configured provider not available, log warning and use highest priority
        if (!configuredProvider.equals("memory")) {
            LOG.warnf("Configured PKCE storage provider '%s' is not available, falling back", configuredProvider);
        }

        // Use highest priority available provider
        if (!availableProviders.isEmpty()) {
            final var provider = availableProviders.get(0);
            LOG.infof("Using PKCE storage provider: %s (priority: %d)", provider.name(), provider.priority());
            return provider;
        }

        // This shouldn't happen as memory provider is always available
        throw new IllegalStateException("No PKCE storage providers available");
    }

    /**
     * Get all available providers (for health checks).
     *
     * @return List of available providers
     */
    public List<PkceStorageProvider> getAvailableProviders() {
        return providers.stream().filter(PkceStorageProvider::isAvailable).toList();
    }
}
