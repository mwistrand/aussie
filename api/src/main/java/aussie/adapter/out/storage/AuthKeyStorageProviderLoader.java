package aussie.adapter.out.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import aussie.adapter.out.storage.redis.RedisAuthKeyCacheProvider;
import aussie.core.port.out.ApiKeyRepository;
import aussie.core.port.out.AuthKeyCache;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.AuthKeyCacheProvider;
import aussie.spi.AuthKeyStorageProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Discovers and loads auth key storage providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.auth.storage.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 *   <li>Cache provider is selected independently (and is optional)</li>
 * </ol>
 */
@ApplicationScoped
public class AuthKeyStorageProviderLoader {

    private static final Logger LOG = Logger.getLogger(AuthKeyStorageProviderLoader.class);

    private final Optional<String> configuredStorageProvider;
    private final Optional<String> configuredCacheProvider;
    private final boolean cacheEnabled;
    private final StorageAdapterConfig config;
    private final Optional<ReactiveRedisDataSource> redisDataSource;

    private AuthKeyStorageProvider storageProvider;
    private AuthKeyCacheProvider cacheProvider;
    private boolean cacheProviderResolved = false;

    @Inject
    public AuthKeyStorageProviderLoader(
            @ConfigProperty(name = "aussie.auth.storage.provider") Optional<String> configuredStorageProvider,
            @ConfigProperty(name = "aussie.auth.cache.provider") Optional<String> configuredCacheProvider,
            @ConfigProperty(name = "aussie.auth.cache.enabled", defaultValue = "false") boolean cacheEnabled,
            StorageAdapterConfig config,
            jakarta.enterprise.inject.Instance<ReactiveRedisDataSource> redisDataSourceInstance) {
        this.configuredStorageProvider = configuredStorageProvider;
        this.configuredCacheProvider = configuredCacheProvider;
        this.cacheEnabled = cacheEnabled;
        this.config = config;
        // Get Redis data source if available
        this.redisDataSource =
                redisDataSourceInstance.isResolvable() ? Optional.of(redisDataSourceInstance.get()) : Optional.empty();
    }

    @Produces
    @ApplicationScoped
    public ApiKeyRepository apiKeyRepository() {
        AuthKeyStorageProvider provider = getStorageProvider();
        LOG.infof("Creating API key repository from provider: %s (%s)", provider.name(), provider.description());
        return provider.createRepository(config);
    }

    /**
     * Produces an AuthKeyCache bean.
     *
     * <p>Returns a no-op cache if caching is disabled or no provider is available.
     *
     * @return AuthKeyCache instance (never null)
     */
    @Produces
    @ApplicationScoped
    public AuthKeyCache authKeyCache() {
        if (!cacheEnabled) {
            LOG.info("Auth key caching disabled (aussie.auth.cache.enabled=false)");
            return NoOpAuthKeyCache.INSTANCE;
        }

        return getCacheProvider()
                .map(provider -> {
                    LOG.infof(
                            "Creating auth key cache from provider: %s (%s)", provider.name(), provider.description());
                    return provider.createCache(config);
                })
                .orElseGet(() -> {
                    LOG.info("No auth key cache provider available, using no-op cache");
                    return NoOpAuthKeyCache.INSTANCE;
                });
    }

    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> authKeyHealthIndicators() {
        List<StorageHealthIndicator> indicators = new ArrayList<>();

        getStorageProvider().createHealthIndicator(config).ifPresent(indicators::add);

        getCacheProvider().flatMap(p -> p.createHealthIndicator(config)).ifPresent(indicators::add);

        return indicators;
    }

    private AuthKeyStorageProvider getStorageProvider() {
        if (storageProvider != null) {
            return storageProvider;
        }

        List<AuthKeyStorageProvider> providers = new ArrayList<>();
        ServiceLoader.load(AuthKeyStorageProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No auth key storage providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d auth key storage provider(s): %s",
                providers.size(),
                providers.stream().map(AuthKeyStorageProvider::name).toList());

        storageProvider = selectProvider(providers, configuredStorageProvider.orElse(null), "storage");

        return storageProvider;
    }

    private Optional<AuthKeyCacheProvider> getCacheProvider() {
        if (cacheProviderResolved) {
            return Optional.ofNullable(cacheProvider);
        }

        List<AuthKeyCacheProvider> providers = new ArrayList<>();
        ServiceLoader.load(AuthKeyCacheProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            LOG.info("No auth key cache providers found on classpath");
            cacheProviderResolved = true;
            return Optional.empty();
        }

        LOG.infof(
                "Found %d auth key cache provider(s): %s",
                providers.size(),
                providers.stream().map(AuthKeyCacheProvider::name).toList());

        try {
            cacheProvider = selectProvider(providers, configuredCacheProvider.orElse(null), "cache");

            // Inject Redis data source if this is the Redis provider
            if (cacheProvider instanceof RedisAuthKeyCacheProvider redisProvider && redisDataSource.isPresent()) {
                redisProvider.setDataSource(redisDataSource.get());
            }

            cacheProviderResolved = true;
            return Optional.of(cacheProvider);
        } catch (StorageProviderException e) {
            LOG.warn("Auth key cache provider not available: " + e.getMessage());
            cacheProviderResolved = true;
            return Optional.empty();
        }
    }

    private <T> T selectProvider(List<? extends T> providers, String configured, String type) {
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(p -> getName(p).equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException("Configured " + type + " provider not found: "
                            + configured + ". Available: "
                            + providers.stream().map(this::getName).toList()));
        }

        return providers.stream()
                .filter(this::isAvailable)
                .max(Comparator.comparingInt(this::getPriority))
                .orElseThrow(() -> new StorageProviderException("No available " + type + " providers"));
    }

    private String getName(Object provider) {
        if (provider instanceof AuthKeyStorageProvider p) {
            return p.name();
        }
        if (provider instanceof AuthKeyCacheProvider p) {
            return p.name();
        }
        throw new IllegalArgumentException("Unknown provider type: " + provider.getClass());
    }

    private boolean isAvailable(Object provider) {
        if (provider instanceof AuthKeyStorageProvider p) {
            return p.isAvailable();
        }
        if (provider instanceof AuthKeyCacheProvider p) {
            return p.isAvailable();
        }
        return false;
    }

    private int getPriority(Object provider) {
        if (provider instanceof AuthKeyStorageProvider p) {
            return p.priority();
        }
        if (provider instanceof AuthKeyCacheProvider p) {
            return p.priority();
        }
        return 0;
    }
}
