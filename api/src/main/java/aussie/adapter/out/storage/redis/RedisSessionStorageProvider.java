package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import io.smallrye.mutiny.subscription.Cancellable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.config.ResiliencyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.port.out.Metrics;
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
    private final ResiliencyConfig resiliencyConfig;
    private final Metrics metrics;
    private final ObjectMapper objectMapper;
    private final boolean clusterClient;

    private RedisSessionRepository repository;

    private enum AvailabilityState {
        CHECKING,
        AVAILABLE,
        UNAVAILABLE
    }

    private final AtomicReference<AvailabilityState> availabilityState =
            new AtomicReference<>(AvailabilityState.CHECKING);
    private final AtomicBoolean availabilityCheckInProgress = new AtomicBoolean();
    private volatile Cancellable availabilityCheck;

    @Inject
    public RedisSessionStorageProvider(
            ReactiveRedisDataSource redisDataSource,
            SessionConfig sessionConfig,
            ResiliencyConfig resiliencyConfig,
            Metrics metrics,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "quarkus.redis.client-type", defaultValue = "standalone") String redisClientType) {
        this.redisDataSource = redisDataSource;
        this.sessionConfig = sessionConfig;
        this.resiliencyConfig = resiliencyConfig;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clusterClient = redisClientType.equalsIgnoreCase("cluster");
    }

    @PostConstruct
    void checkAvailability() {
        refreshAvailability();
    }

    @Scheduled(every = "5s", concurrentExecution = ConcurrentExecution.SKIP)
    void refreshAvailability() {
        if (clusterClient) {
            availabilityState.set(AvailabilityState.UNAVAILABLE);
            return;
        }
        if (!availabilityCheckInProgress.compareAndSet(false, true)) {
            return;
        }
        if (availabilityState.get() != AvailabilityState.AVAILABLE) {
            availabilityState.set(AvailabilityState.CHECKING);
        }
        try {
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
                                LOG.info("Redis session storage is available");
                                availabilityCheckInProgress.set(false);
                            },
                            error -> {
                                availabilityState.set(AvailabilityState.UNAVAILABLE);
                                LOG.warnf("Redis session storage is not available: %s", error.getMessage());
                                availabilityCheckInProgress.set(false);
                            });
        } catch (RuntimeException error) {
            availabilityState.set(AvailabilityState.UNAVAILABLE);
            availabilityCheckInProgress.set(false);
            LOG.warnf("Redis session storage is not available: %s", error.getMessage());
        }
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
        return availabilityState.get() == AvailabilityState.AVAILABLE;
    }

    @Override
    public SessionRepository createRepository() {
        if (clusterClient) {
            // shortcut: session records and user indexes span slots, redesign the key schema before cluster support.
            throw new IllegalStateException("Redis session storage does not support Redis Cluster");
        }
        if (repository == null) {
            var timeoutHelper =
                    new RedisTimeoutHelper(resiliencyConfig.redis().operationTimeout(), metrics, "SessionRepository");
            repository = new RedisSessionRepository(redisDataSource, sessionConfig, timeoutHelper, objectMapper);
            LOG.info("Created Redis session repository with prefix: "
                    + sessionConfig.storage().redis().keyPrefix());
        }
        return repository;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        if (availabilityState.get() == AvailabilityState.AVAILABLE) {
            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .up()
                    .withData("type", "redis")
                    .withData("keyPrefix", sessionConfig.storage().redis().keyPrefix())
                    .build());
        } else {
            final var error = availabilityState.get() == AvailabilityState.CHECKING
                    ? "Availability check in progress"
                    : clusterClient ? "Redis Cluster is not supported" : "Redis not available";
            return Optional.of(HealthCheckResponse.named("session-storage-redis")
                    .down()
                    .withData("type", "redis")
                    .withData("error", error)
                    .build());
        }
    }
}
