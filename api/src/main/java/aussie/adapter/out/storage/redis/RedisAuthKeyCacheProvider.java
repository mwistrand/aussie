package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.Uni;

import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.AuthKeyCache;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.AuthKeyCacheProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Redis cache provider for API key authentication.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>aussie.auth.cache.ttl - Cache entry TTL in ISO-8601 format (default: PT5M)</li>
 * </ul>
 *
 * <p>Redis connection is configured via Quarkus Redis extension:
 * <ul>
 *   <li>quarkus.redis.hosts - Redis server URL</li>
 *   <li>quarkus.redis.password - Redis password (optional)</li>
 * </ul>
 */
public class RedisAuthKeyCacheProvider implements AuthKeyCacheProvider {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private ReactiveRedisDataSource dataSource;

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public String description() {
        return "Redis distributed cache for API key authentication";
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
     * Set the Redis data source.
     *
     * <p>This is called by the AuthKeyStorageProviderLoader to inject the
     * Quarkus-managed Redis data source.
     *
     * @param dataSource the Redis data source
     */
    public void setDataSource(ReactiveRedisDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AuthKeyCache createCache(StorageAdapterConfig config) {
        if (dataSource == null) {
            throw new StorageProviderException(
                    "Redis data source not set. Ensure RedisAuthKeyCacheProvider.setDataSource() is called.");
        }

        Duration ttl = config.getDuration("aussie.auth.cache.ttl").orElse(DEFAULT_TTL);
        return new RedisAuthKeyCache(dataSource, ttl);
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (dataSource == null) {
                return Uni.createFrom().item(StorageHealth.unhealthy("redis-auth", "Data source not initialized"));
            }

            long start = System.currentTimeMillis();
            return dataSource
                    .execute("PING")
                    .map(response -> {
                        long latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("redis-auth", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("redis-auth", e.getMessage()));
        });
    }
}
