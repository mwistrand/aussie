package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
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

    private final ReactiveRedisDataSource redisDataSource;
    private final SessionConfig sessionConfig;

    private RedisSessionRepository repository;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final CountDownLatch checkLatch = new CountDownLatch(1);

    @Inject
    public RedisSessionStorageProvider(ReactiveRedisDataSource redisDataSource, SessionConfig sessionConfig) {
        this.redisDataSource = redisDataSource;
        this.sessionConfig = sessionConfig;
    }

    @PostConstruct
    void checkAvailability() {
        // Check availability asynchronously at startup
        redisDataSource
                .key(String.class)
                .exists("test-connection")
                .ifNoItem()
                .after(Duration.ofSeconds(5))
                .fail()
                .subscribe()
                .with(
                        result -> {
                            available.set(true);
                            checkLatch.countDown();
                            LOG.info("Redis session storage is available");
                        },
                        error -> {
                            available.set(false);
                            checkLatch.countDown();
                            LOG.warnf("Redis session storage is not available: %s", error.getMessage());
                        });
    }

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
        // Wait for the async check to complete (with timeout)
        try {
            if (!checkLatch.await(6, TimeUnit.SECONDS)) {
                LOG.warn("Redis availability check timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return available.get();
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
        // Use cached availability state to avoid blocking on health checks
        if (available.get()) {
            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .up()
                    .withData("type", "redis")
                    .withData("keyPrefix", sessionConfig.storage().redis().keyPrefix())
                    .build());
        } else {
            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .down()
                    .withData("type", "redis")
                    .withData("error", "Redis not available or check not completed")
                    .build());
        }
    }
}
