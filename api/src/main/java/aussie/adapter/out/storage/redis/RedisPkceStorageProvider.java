package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.smallrye.mutiny.subscription.Cancellable;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;
import aussie.spi.PkceStorageProvider;

/**
 * Redis-based PKCE storage provider.
 *
 * <p>This is the recommended provider for production deployments.
 * PKCE challenges are stored in Redis with automatic TTL expiration.
 */
@ApplicationScoped
public class RedisPkceStorageProvider implements PkceStorageProvider {

    private static final Logger LOG = Logger.getLogger(RedisPkceStorageProvider.class);
    private static final int PRIORITY = 100;

    private enum AvailabilityState {
        CHECKING,
        AVAILABLE,
        UNAVAILABLE
    }

    private final ReactiveRedisDataSource redisDataSource;
    private final PkceConfig pkceConfig;

    private volatile RedisPkceChallengeRepository repository;
    private final AtomicReference<AvailabilityState> availabilityState =
            new AtomicReference<>(AvailabilityState.CHECKING);
    private volatile Cancellable availabilityCheck;

    @Inject
    public RedisPkceStorageProvider(ReactiveRedisDataSource redisDataSource, PkceConfig pkceConfig) {
        this.redisDataSource = redisDataSource;
        this.pkceConfig = pkceConfig;
    }

    @PostConstruct
    void checkAvailability() {
        // Check availability asynchronously at startup
        availabilityCheck = redisDataSource
                .key(String.class)
                .exists("test-connection")
                .ifNoItem()
                .after(Duration.ofSeconds(5))
                .fail()
                .subscribe()
                .with(
                        result -> {
                            availabilityState.set(AvailabilityState.AVAILABLE);
                            LOG.info("Redis PKCE storage is available");
                        },
                        error -> {
                            availabilityState.set(AvailabilityState.UNAVAILABLE);
                            LOG.warnf("Redis PKCE storage is not available: %s", error.getMessage());
                        });
    }

    @PreDestroy
    void close() {
        if (availabilityCheck != null) {
            availabilityCheck.cancel();
            availabilityCheck = null;
        }
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
        // Non-blocking check; providers still being checked are ineligible for automatic selection.
        // Explicit configuration retains this provider so repository operations fail closed.
        return availabilityState.get() == AvailabilityState.AVAILABLE;
    }

    @Override
    public synchronized PkceChallengeRepository createRepository() {
        if (repository == null) {
            repository = new RedisPkceChallengeRepository(redisDataSource, pkceConfig);
            LOG.info("Created Redis PKCE challenge repository with prefix: "
                    + pkceConfig.storage().redis().keyPrefix());
        }
        return repository;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        final var state = availabilityState.get();
        if (state == AvailabilityState.AVAILABLE) {
            return Optional.of(HealthCheckResponse.named("pkce-storage-redis")
                    .up()
                    .withData("type", "redis")
                    .withData("keyPrefix", pkceConfig.storage().redis().keyPrefix())
                    .build());
        } else {
            final var error =
                    state == AvailabilityState.CHECKING ? "Availability check in progress" : "Redis not available";
            return Optional.of(HealthCheckResponse.named("pkce-storage-redis")
                    .down()
                    .withData("type", "redis")
                    .withData("error", error)
                    .build());
        }
    }
}
