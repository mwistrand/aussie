package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.inject.spi.CDI;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;

import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.ConfigurationCacheProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Redis cache provider.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>aussie.storage.cache.ttl - Cache TTL in ISO-8601 duration format (default: PT15M)</li>
 * </ul>
 *
 * <p>Redis connection is configured via Quarkus Redis properties:
 * <ul>
 *   <li>quarkus.redis.hosts - Redis server URL (default: redis://localhost:6379)</li>
 *   <li>quarkus.redis.password - Redis password (optional)</li>
 *   <li>quarkus.redis.database - Redis database index (default: 0)</li>
 * </ul>
 */
public class RedisCacheProvider implements ConfigurationCacheProvider {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private ReactiveRedisDataSource dataSource;

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public String description() {
        return "Redis distributed cache";
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

    @Override
    public ConfigurationCache createCache(StorageAdapterConfig config) {
        Duration ttl = config.getDuration("aussie.storage.cache.ttl").orElse(DEFAULT_TTL);

        // Get the Redis data source from CDI
        try {
            this.dataSource =
                    CDI.current().select(ReactiveRedisDataSource.class).get();
        } catch (Exception e) {
            throw new StorageProviderException("Failed to obtain Redis data source from CDI", e);
        }

        return new RedisConfigurationCache(dataSource, ttl);
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (dataSource == null) {
                return Uni.createFrom().item(StorageHealth.unhealthy("redis", "Data source not initialized"));
            }

            long start = System.currentTimeMillis();
            return dataSource
                    .value(String.class, String.class)
                    .get("aussie:health:ping")
                    .map(result -> {
                        long latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("redis", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("redis", e.getMessage()));
        });
    }
}
