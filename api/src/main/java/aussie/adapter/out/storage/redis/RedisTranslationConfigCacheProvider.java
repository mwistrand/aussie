package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigCache;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.TranslationConfigCacheProvider;

/**
 * Redis cache provider for translation configurations.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>aussie.translation-config.cache.redis.ttl - Cache TTL (default: PT15M)</li>
 *   <li>quarkus.redis.hosts - Redis connection URL (e.g., redis://localhost:6379)</li>
 * </ul>
 *
 * <p>This provider requires the Quarkus Redis extension to be available.
 */
public class RedisTranslationConfigCacheProvider implements TranslationConfigCacheProvider {

    private static final Logger LOG = Logger.getLogger(RedisTranslationConfigCacheProvider.class);
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private ReactiveRedisDataSource dataSource;

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public String description() {
        return "Redis distributed cache for translation configs";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("io.quarkus.redis.datasource.ReactiveRedisDataSource");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Sets the Redis data source for CDI injection.
     *
     * <p>This method is called by the loader when the data source is available
     * via CDI injection rather than configuration.
     *
     * @param dataSource the Redis data source
     */
    public void setDataSource(ReactiveRedisDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public TranslationConfigCache createCache(StorageAdapterConfig config) {
        if (dataSource == null) {
            throw new IllegalStateException("Redis data source not set. Call setDataSource() before createCache()");
        }

        final var ttl =
                config.getDuration("aussie.translation-config.cache.redis.ttl").orElse(DEFAULT_TTL);
        LOG.infof("Created Redis translation config cache (TTL: %s)", ttl);
        return new RedisTranslationConfigCache(dataSource, ttl);
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (dataSource == null) {
                return Uni.createFrom()
                        .item(StorageHealth.unhealthy("redis-translation-config-cache", "Data source not initialized"));
            }

            final var start = System.currentTimeMillis();
            return dataSource
                    .value(String.class, String.class)
                    .get("__health_check__")
                    .map(v -> {
                        final var latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("redis-translation-config-cache", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("redis-translation-config-cache", e.getMessage()));
        });
    }
}
