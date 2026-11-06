package aussie.core.service.session;

import java.util.Comparator;
import java.util.List;

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
 *   <li>Highest priority available provider when no provider is configured</li>
 * </ol>
 */
@ApplicationScoped
public class SessionStorageProviderRegistry {

    private static final Logger LOG = Logger.getLogger(SessionStorageProviderRegistry.class);

    private final Instance<SessionStorageProvider> providers;
    private final SessionConfig config;

    private volatile SessionStorageProvider selectedProvider;
    private volatile SessionRepository repository;

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
    public synchronized SessionRepository getRepository() {
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
    public synchronized SessionStorageProvider getSelectedProvider() {
        if (selectedProvider == null) {
            selectedProvider = selectProvider();
        }
        return selectedProvider;
    }

    private SessionStorageProvider selectProvider() {
        final var configuredProvider = config.storage().provider();
        if (configuredProvider != null && !configuredProvider.isBlank()) {
            final var configured = providers.stream()
                    .filter(p -> p.name().equals(configuredProvider))
                    .findFirst();
            if (configured.isEmpty()) {
                throw new IllegalStateException("Configured session storage provider not found: " + configuredProvider);
            }
            LOG.infof("Using configured session storage provider: %s", configuredProvider);
            return configured.get();
        }

        final List<SessionStorageProvider> availableProviders = providers.stream()
                .filter(SessionStorageProvider::isAvailable)
                .sorted(Comparator.comparingInt(SessionStorageProvider::priority)
                        .reversed())
                .toList();

        LOG.debugf(
                "Available session storage providers: %s",
                availableProviders.stream().map(SessionStorageProvider::name).toList());

        if (!availableProviders.isEmpty()) {
            SessionStorageProvider provider = availableProviders.get(0);
            LOG.infof("Using session storage provider: %s (priority: %d)", provider.name(), provider.priority());
            return provider;
        }

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
