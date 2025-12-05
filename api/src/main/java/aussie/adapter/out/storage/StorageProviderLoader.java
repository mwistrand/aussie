package aussie.adapter.out.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.ConfigurationCacheProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;
import aussie.spi.StorageRepositoryProvider;

/**
 * Discovers and loads storage providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.storage.repository.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 *   <li>Cache provider is selected independently (and is optional)</li>
 * </ol>
 */
@ApplicationScoped
public class StorageProviderLoader {

    private static final Logger LOG = Logger.getLogger(StorageProviderLoader.class);

    private final Optional<String> configuredRepositoryProvider;
    private final Optional<String> configuredCacheProvider;
    private final boolean cacheEnabled;
    private final StorageAdapterConfig config;

    private StorageRepositoryProvider repositoryProvider;
    private ConfigurationCacheProvider cacheProvider;
    private boolean cacheProviderResolved = false;

    @Inject
    public StorageProviderLoader(
            @ConfigProperty(name = "aussie.storage.repository.provider") Optional<String> configuredRepositoryProvider,
            @ConfigProperty(name = "aussie.storage.cache.provider") Optional<String> configuredCacheProvider,
            @ConfigProperty(name = "aussie.storage.cache.enabled", defaultValue = "false") boolean cacheEnabled,
            StorageAdapterConfig config) {
        this.configuredRepositoryProvider = configuredRepositoryProvider;
        this.configuredCacheProvider = configuredCacheProvider;
        this.cacheEnabled = cacheEnabled;
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public ServiceRegistrationRepository repository() {
        StorageRepositoryProvider provider = getRepositoryProvider();
        LOG.infof("Creating repository from provider: %s (%s)", provider.name(), provider.description());
        return provider.createRepository(config);
    }

    /**
     * Produces a ConfigurationCache bean.
     *
     * <p>Returns a no-op cache if caching is disabled or no provider is available.
     * The ServiceRegistry checks for NoOpConfigurationCache and treats it as "no cache".
     *
     * @return ConfigurationCache instance (never null)
     */
    @Produces
    @ApplicationScoped
    public ConfigurationCache cache() {
        if (!cacheEnabled) {
            LOG.info("Caching disabled (aussie.storage.cache.enabled=false)");
            return NoOpConfigurationCache.INSTANCE;
        }

        return getCacheProvider()
                .map(provider -> {
                    LOG.infof("Creating cache from provider: %s (%s)", provider.name(), provider.description());
                    return provider.createCache(config);
                })
                .orElseGet(() -> {
                    LOG.info("No cache provider available, using no-op cache");
                    return NoOpConfigurationCache.INSTANCE;
                });
    }

    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> healthIndicators() {
        List<StorageHealthIndicator> indicators = new ArrayList<>();

        getRepositoryProvider().createHealthIndicator(config).ifPresent(indicators::add);

        getCacheProvider().flatMap(p -> p.createHealthIndicator(config)).ifPresent(indicators::add);

        return indicators;
    }

    private StorageRepositoryProvider getRepositoryProvider() {
        if (repositoryProvider != null) {
            return repositoryProvider;
        }

        List<StorageRepositoryProvider> providers = new ArrayList<>();
        ServiceLoader.load(StorageRepositoryProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No storage repository providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d repository provider(s): %s",
                providers.size(),
                providers.stream().map(StorageRepositoryProvider::name).toList());

        repositoryProvider = selectProvider(providers, configuredRepositoryProvider.orElse(null), "repository");

        return repositoryProvider;
    }

    private Optional<ConfigurationCacheProvider> getCacheProvider() {
        if (cacheProviderResolved) {
            return Optional.ofNullable(cacheProvider);
        }

        List<ConfigurationCacheProvider> providers = new ArrayList<>();
        ServiceLoader.load(ConfigurationCacheProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            LOG.info("No cache providers found on classpath");
            cacheProviderResolved = true;
            return Optional.empty();
        }

        LOG.infof(
                "Found %d cache provider(s): %s",
                providers.size(),
                providers.stream().map(ConfigurationCacheProvider::name).toList());

        try {
            cacheProvider = selectProvider(providers, configuredCacheProvider.orElse(null), "cache");
            cacheProviderResolved = true;
            return Optional.of(cacheProvider);
        } catch (StorageProviderException e) {
            LOG.warn("Cache provider not available: " + e.getMessage());
            cacheProviderResolved = true;
            return Optional.empty();
        }
    }

    private <T> T selectProvider(List<? extends T> providers, String configured, String type) {
        // Explicit configuration takes precedence
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(p -> getName(p).equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException("Configured " + type + " provider not found: "
                            + configured + ". Available: "
                            + providers.stream().map(this::getName).toList()));
        }

        // Otherwise, select by priority from available providers
        return providers.stream()
                .filter(this::isAvailable)
                .max(Comparator.comparingInt(this::getPriority))
                .orElseThrow(() -> new StorageProviderException("No available " + type + " providers"));
    }

    private String getName(Object provider) {
        if (provider instanceof StorageRepositoryProvider p) {
            return p.name();
        }
        if (provider instanceof ConfigurationCacheProvider p) {
            return p.name();
        }
        throw new IllegalArgumentException("Unknown provider type: " + provider.getClass());
    }

    private boolean isAvailable(Object provider) {
        if (provider instanceof StorageRepositoryProvider p) {
            return p.isAvailable();
        }
        if (provider instanceof ConfigurationCacheProvider p) {
            return p.isAvailable();
        }
        return false;
    }

    private int getPriority(Object provider) {
        if (provider instanceof StorageRepositoryProvider p) {
            return p.priority();
        }
        if (provider instanceof ConfigurationCacheProvider p) {
            return p.priority();
        }
        return 0;
    }
}
