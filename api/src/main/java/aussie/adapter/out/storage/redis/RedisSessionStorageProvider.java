package aussie.adapter.out.storage.redis;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.config.SessionConfigMapping;
import aussie.core.port.out.SessionRepository;
import aussie.spi.SessionStorageProvider;

/**
 * Redis-based session storage provider.
 *
 * <p>This is the recommended provider for production deployments.
 * Sessions are persisted in Redis with automatic TTL expiration.
 */
@ApplicationScoped
public class RedisSessionStorageProvider implements SessionStorageProvider {

    private static final Logger LOG = Logger.getLogger(RedisSessionStorageProvider.class);
    private static final int PRIORITY = 100; // High priority

    @Inject
    ReactiveRedisDataSource redisDataSource;

    @Inject
    SessionConfigMapping sessionConfig;

    private RedisSessionRepository repository;
    private Boolean available;

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        if (available != null) {
            return available;
        }

        try {
            // Test Redis connection
            redisDataSource.key(String.class).exists("test-connection").await().atMost(java.time.Duration.ofSeconds(5));
            available = true;
            LOG.info("Redis session storage is available");
        } catch (Exception e) {
            available = false;
            LOG.warnf("Redis session storage is not available: %s", e.getMessage());
        }

        return available;
    }

    @Override
    public SessionRepository createRepository() {
        if (repository == null) {
            repository = new RedisSessionRepository(redisDataSource, sessionConfig);
            LOG.info("Created Redis session repository with prefix: "
                    + sessionConfig.storage().redis().keyPrefix());
        }
        return repository;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        try {
            redisDataSource.key(String.class).exists("health-check").await().atMost(java.time.Duration.ofSeconds(2));

            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .up()
                    .withData("type", "redis")
                    .withData("keyPrefix", sessionConfig.storage().redis().keyPrefix())
                    .build());
        } catch (Exception e) {
            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .down()
                    .withData("type", "redis")
                    .withData("error", e.getMessage())
                    .build());
        }
    }
}
