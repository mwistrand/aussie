package aussie.core.service.session;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.port.out.SessionRepository;
import aussie.spi.SessionStorageProvider;

/**
 * Registry for session storage providers.
 *
 * <p>Discovers available providers via CDI and selects the appropriate one
 * based on configuration and availability.
 *
 * <p>Selection order:
 * <ol>
 *   <li>Configured provider (aussie.session.storage.provider)</li>
 *   <li>Highest priority available provider</li>
 *   <li>Memory fallback (always available)</li>
 * </ol>
 */
@ApplicationScoped
public class SessionStorageProviderRegistry {

    private static final Logger LOG = Logger.getLogger(SessionStorageProviderRegistry.class);

    private final Instance<SessionStorageProvider> providers;
    private final SessionConfig config;

    private SessionStorageProvider selectedProvider;
    private SessionRepository repository;

    @Inject
    public SessionStorageProviderRegistry(Instance<SessionStorageProvider> providers, SessionConfig config) {
        this.providers = providers;
        this.config = config;
    }

    /**
     * Initialize provider selection at startup.
     *
     * <p>This ensures provider selection happens during application startup
     * (on a worker thread) rather than lazily during the first request
     * (which might be on the Vert.x event loop where blocking is forbidden).
     *
     * <p>Using StartupEvent (rather than @PostConstruct) ensures this runs after
     * all CDI beans are initialized, including provider availability checks.
     */
    void onStart(@Observes StartupEvent event) {
        // Eagerly select provider at startup to avoid blocking on event loop
        getSelectedProvider();
        LOG.infof("Session storage provider initialized: %s", selectedProvider.name());
    }

    /**
     * Get the session repository from the selected provider.
     *
     * @return Session repository instance
     */
    public SessionRepository getRepository() {
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
    public SessionStorageProvider getSelectedProvider() {
        if (selectedProvider == null) {
            selectedProvider = selectProvider();
        }
        return selectedProvider;
    }

    private SessionStorageProvider selectProvider() {
        String configuredProvider = config.storage().provider();
        List<SessionStorageProvider> availableProviders = providers.stream()
                .filter(SessionStorageProvider::isAvailable)
                .sorted(Comparator.comparingInt(SessionStorageProvider::priority)
                        .reversed())
                .toList();

        LOG.debugf(
                "Available session storage providers: %s",
                availableProviders.stream().map(SessionStorageProvider::name).toList());

        // First, try to find the configured provider
        Optional<SessionStorageProvider> configured = availableProviders.stream()
                .filter(p -> p.name().equals(configuredProvider))
                .findFirst();

        if (configured.isPresent()) {
            LOG.infof("Using configured session storage provider: %s", configuredProvider);
            return configured.get();
        }

        // If configured provider not available, log warning and use highest priority
        if (!configuredProvider.equals("memory")) {
            LOG.warnf("Configured session storage provider '%s' is not available, falling back", configuredProvider);
        }

        // Use highest priority available provider
        if (!availableProviders.isEmpty()) {
            SessionStorageProvider provider = availableProviders.get(0);
            LOG.infof("Using session storage provider: %s (priority: %d)", provider.name(), provider.priority());
            return provider;
        }

        // This shouldn't happen as memory provider is always available
        throw new IllegalStateException("No session storage providers available");
    }

    /**
     * Get all available providers (for health checks).
     *
     * @return List of available providers
     */
    public List<SessionStorageProvider> getAvailableProviders() {
        return providers.stream().filter(SessionStorageProvider::isAvailable).toList();
    }
}
