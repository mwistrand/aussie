package aussie.adapter.out.storage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import aussie.adapter.out.storage.redis.RedisTranslationConfigCacheProvider;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigCache;
import aussie.core.port.out.TranslationConfigRepository;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;
import aussie.spi.TranslationConfigCacheProvider;
import aussie.spi.TranslationConfigStorageProvider;

/**
 * Discovers and loads translation config storage providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.translation-config.storage.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 *   <li>Cache provider is selected independently (and is optional)</li>
 * </ol>
 *
 * <p>When a storage provider and optional cache provider are selected, they are
 * wrapped in a {@link TieredTranslationConfigRepository} which adds a local
 * in-memory cache layer.
 */
@ApplicationScoped
public class TranslationConfigStorageLoader {

    private static final Logger LOG = Logger.getLogger(TranslationConfigStorageLoader.class);

    private final Optional<String> configuredStorageProvider;
    private final Optional<String> configuredCacheProvider;
    private final boolean cacheEnabled;
    private final Duration memoryCacheTtl;
    private final long memoryCacheMaxSize;
    private final StorageAdapterConfig config;
    private final Instance<ReactiveRedisDataSource> redisDataSource;

    private TranslationConfigStorageProvider storageProvider;
    private TranslationConfigCacheProvider cacheProvider;
    private boolean cacheProviderResolved = false;

    @Inject
    public TranslationConfigStorageLoader(
            @ConfigProperty(name = "aussie.translation-config.storage.provider")
                    Optional<String> configuredStorageProvider,
            @ConfigProperty(name = "aussie.translation-config.cache.provider") Optional<String> configuredCacheProvider,
            @ConfigProperty(name = "aussie.translation-config.cache.enabled", defaultValue = "true")
                    boolean cacheEnabled,
            @ConfigProperty(name = "aussie.translation-config.cache.memory.ttl", defaultValue = "PT5M")
                    Duration memoryCacheTtl,
            @ConfigProperty(name = "aussie.translation-config.cache.memory.max-size", defaultValue = "100")
                    long memoryCacheMaxSize,
            StorageAdapterConfig config,
            Instance<ReactiveRedisDataSource> redisDataSource) {
        this.configuredStorageProvider = configuredStorageProvider;
        this.configuredCacheProvider = configuredCacheProvider;
        this.cacheEnabled = cacheEnabled;
        this.memoryCacheTtl = memoryCacheTtl;
        this.memoryCacheMaxSize = memoryCacheMaxSize;
        this.config = config;
        this.redisDataSource = redisDataSource;
    }

    @Produces
    @ApplicationScoped
    public TranslationConfigRepository repository() {
        final var provider = getStorageProvider();
        LOG.infof(
                "Creating translation config repository from provider: %s (%s)",
                provider.name(), provider.description());

        final var primaryRepository = provider.createRepository(config);

        // Wrap with tiered caching
        TranslationConfigCache distributedCache = null;
        if (cacheEnabled) {
            distributedCache = getCacheProvider()
                    .map(cp -> {
                        LOG.infof(
                                "Creating translation config cache from provider: %s (%s)",
                                cp.name(), cp.description());
                        return cp.createCache(config);
                    })
                    .orElse(null);
        }

        if (distributedCache == null && cacheEnabled) {
            LOG.info("No distributed cache available, using local cache only");
        }

        LOG.infof(
                "Creating tiered translation config repository (memory TTL: %s, max size: %d, distributed cache: %s)",
                memoryCacheTtl, memoryCacheMaxSize, distributedCache != null ? "enabled" : "disabled");

        return new TieredTranslationConfigRepository(
                primaryRepository, distributedCache, memoryCacheTtl, memoryCacheMaxSize);
    }

    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> translationConfigHealthIndicators() {
        final List<StorageHealthIndicator> indicators = new ArrayList<>();

        getStorageProvider().createHealthIndicator(config).ifPresent(indicators::add);
        getCacheProvider().flatMap(p -> p.createHealthIndicator(config)).ifPresent(indicators::add);

        return indicators;
    }

    private synchronized TranslationConfigStorageProvider getStorageProvider() {
        if (storageProvider != null) {
            return storageProvider;
        }

        final List<TranslationConfigStorageProvider> providers = new ArrayList<>();
        ServiceLoader.load(TranslationConfigStorageProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No translation config storage providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d translation config storage provider(s): %s",
                providers.size(),
                providers.stream().map(TranslationConfigStorageProvider::name).toList());

        storageProvider = selectProvider(providers, configuredStorageProvider.orElse(null), "storage");
        return storageProvider;
    }

    private synchronized Optional<TranslationConfigCacheProvider> getCacheProvider() {
        if (cacheProviderResolved) {
            return Optional.ofNullable(cacheProvider);
        }

        final List<TranslationConfigCacheProvider> providers = new ArrayList<>();
        ServiceLoader.load(TranslationConfigCacheProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            LOG.info("No translation config cache providers found on classpath");
            cacheProviderResolved = true;
            return Optional.empty();
        }

        LOG.infof(
                "Found %d translation config cache provider(s): %s",
                providers.size(),
                providers.stream().map(TranslationConfigCacheProvider::name).toList());

        try {
            cacheProvider = selectProvider(providers, configuredCacheProvider.orElse(null), "cache");

            // Inject Redis data source if available
            if (cacheProvider instanceof RedisTranslationConfigCacheProvider redisCacheProvider) {
                if (redisDataSource.isResolvable()) {
                    redisCacheProvider.setDataSource(redisDataSource.get());
                } else {
                    LOG.warn("Redis cache provider selected but Redis data source not available");
                    cacheProvider = null;
                }
            }

            cacheProviderResolved = true;
            return Optional.ofNullable(cacheProvider);
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
                    .orElseThrow(() -> new StorageProviderException(
                            "Configured translation config " + type + " provider not found: "
                                    + configured + ". Available: "
                                    + providers.stream().map(this::getName).toList()));
        }

        // Otherwise, select by priority from available providers
        return providers.stream()
                .filter(this::isAvailable)
                .max(Comparator.comparingInt(this::getPriority))
                .orElseThrow(
                        () -> new StorageProviderException("No available translation config " + type + " providers"));
    }

    private String getName(Object provider) {
        if (provider instanceof TranslationConfigStorageProvider p) {
            return p.name();
        }
        if (provider instanceof TranslationConfigCacheProvider p) {
            return p.name();
        }
        throw new IllegalArgumentException("Unknown provider type: " + provider.getClass());
    }

    private boolean isAvailable(Object provider) {
        if (provider instanceof TranslationConfigStorageProvider p) {
            return p.isAvailable();
        }
        if (provider instanceof TranslationConfigCacheProvider p) {
            return p.isAvailable();
        }
        return false;
    }

    private int getPriority(Object provider) {
        if (provider instanceof TranslationConfigStorageProvider p) {
            return p.priority();
        }
        if (provider instanceof TranslationConfigCacheProvider p) {
            return p.priority();
        }
        return 0;
    }
}
