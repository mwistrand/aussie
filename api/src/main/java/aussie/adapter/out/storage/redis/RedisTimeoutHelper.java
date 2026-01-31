package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.port.out.Metrics;

/**
 * Helper for applying timeouts and failure handling to Redis operations with graceful degradation.
 *
 * <p>Provides consistent timeout and error handling across all Redis repositories with
 * configurable fallback behavior based on operation criticality. Handles both timeouts
 * (operations that take too long) and failures (connection errors, server errors, etc.).
 *
 * <h2>Operation Modes</h2>
 * <ul>
 *   <li>{@link #withTimeout} - Fail-fast: throws {@link RedisTimeoutException} on timeout,
 *       propagates other failures. Use for critical operations (e.g., session management).</li>
 *   <li>{@link #withTimeoutGraceful} - Fail-soft: returns empty Optional on timeout or any failure.
 *       Use for cache reads where missing data is acceptable.</li>
 *   <li>{@link #withTimeoutFallback} - Fail-open: returns custom fallback on timeout or any failure.
 *       Use for rate limiting where failure should allow the request.</li>
 *   <li>{@link #withTimeoutSilent} - Fire-and-forget: logs but ignores timeout or any failure.
 *       Use for cache writes that are not critical.</li>
 * </ul>
 *
 * <h2>Metrics</h2>
 * Records separate metrics for timeouts ({@code aussie.redis.timeouts.total}) and
 * non-timeout failures ({@code aussie.redis.failures.total}).
 */
public class RedisTimeoutHelper {

    private static final Logger LOG = Logger.getLogger(RedisTimeoutHelper.class);

    private final Duration timeout;
    private final Metrics metrics;
    private final String repositoryName;

    /**
     * Create a new timeout helper.
     *
     * @param timeout the timeout duration for Redis operations
     * @param metrics the metrics instance for recording timeouts (may be null)
     * @param repositoryName the repository name for metrics tagging
     */
    public RedisTimeoutHelper(Duration timeout, Metrics metrics, String repositoryName) {
        this.timeout = timeout;
        this.metrics = metrics;
        this.repositoryName = repositoryName;
    }

    /**
     * Apply timeout to an operation that should fail on timeout.
     *
     * <p>Use this for critical operations like session management where timeout
     * should propagate as an error. Note that this method only converts timeouts
     * to {@link RedisTimeoutException}; other failures (connection errors, server
     * errors) are propagated unchanged.
     *
     * @param operation the Redis operation
     * @param operationName name for logging and metrics
     * @param <T> the result type
     * @return a Uni that fails with RedisTimeoutException on timeout; other failures propagate
     */
    public <T> Uni<T> withTimeout(Uni<T> operation, String operationName) {
        return operation.ifNoItem().after(timeout).failWith(() -> {
            LOG.warnv("Redis operation timeout: {0} in {1} after {2}", operationName, repositoryName, timeout);
            recordTimeout(operationName);
            return new RedisTimeoutException(operationName, repositoryName);
        });
    }

    /**
     * Apply timeout with graceful degradation to empty Optional.
     *
     * <p>Use this for cache read operations where timeout or any operational failure
     * should be treated as a cache miss rather than an error. This includes timeouts,
     * connection exceptions, and server errors.
     *
     * @param operation the Redis operation
     * @param operationName name for logging and metrics
     * @param <T> the result type
     * @return a Uni that returns empty Optional on timeout or failure
     */
    public <T> Uni<Optional<T>> withTimeoutGraceful(Uni<T> operation, String operationName) {
        return operation
                .map(Optional::ofNullable)
                .ifNoItem()
                .after(timeout)
                .recoverWithItem(() -> {
                    LOG.warnv(
                            "Redis operation timeout (graceful): {0} in {1} after {2}",
                            operationName, repositoryName, timeout);
                    recordTimeout(operationName);
                    return Optional.empty();
                })
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(
                            "Redis operation failure (graceful): {0} in {1}: {2}",
                            operationName, repositoryName, error.getMessage());
                    recordFailure(operationName);
                    return Optional.empty();
                });
    }

    /**
     * Apply timeout with graceful degradation to custom fallback.
     *
     * <p>Use this for operations with specific fallback behavior, like rate limiting
     * that should fail-open on timeout or any operational failure.
     *
     * @param operation the Redis operation
     * @param operationName name for logging and metrics
     * @param fallback supplier for fallback value on timeout or failure
     * @param <T> the result type
     * @return a Uni that returns fallback value on timeout or failure
     */
    public <T> Uni<T> withTimeoutFallback(Uni<T> operation, String operationName, Supplier<T> fallback) {
        return operation
                .ifNoItem()
                .after(timeout)
                .recoverWithItem(() -> {
                    LOG.warnv(
                            "Redis operation timeout (fallback): {0} in {1} after {2}",
                            operationName, repositoryName, timeout);
                    recordTimeout(operationName);
                    return fallback.get();
                })
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(
                            "Redis operation failure (fallback): {0} in {1}: {2}",
                            operationName, repositoryName, error.getMessage());
                    recordFailure(operationName);
                    return fallback.get();
                });
    }

    /**
     * Apply timeout with silent failure (fire-and-forget operations).
     *
     * <p>Use this for cache write operations where timeout or any operational failure
     * should be logged but not affect the main operation flow.
     *
     * @param operation the Redis operation
     * @param operationName name for logging and metrics
     * @return a Uni that completes with void on timeout or failure
     */
    public Uni<Void> withTimeoutSilent(Uni<Void> operation, String operationName) {
        return operation
                .ifNoItem()
                .after(timeout)
                .recoverWithItem(() -> {
                    LOG.warnv(
                            "Redis operation timeout (silent): {0} in {1} after {2}",
                            operationName, repositoryName, timeout);
                    recordTimeout(operationName);
                    return null;
                })
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv(
                            "Redis operation failure (silent): {0} in {1}: {2}",
                            operationName, repositoryName, error.getMessage());
                    recordFailure(operationName);
                    return null;
                });
    }

    private void recordTimeout(String operationName) {
        if (metrics != null) {
            metrics.recordRedisTimeout(repositoryName, operationName);
        }
    }

    private void recordFailure(String operationName) {
        if (metrics != null) {
            metrics.recordRedisFailure(repositoryName, operationName);
        }
    }

    /**
     * Exception indicating a Redis operation timeout.
     *
     * <p>Thrown by {@link #withTimeout} when an operation exceeds the configured timeout.
     * Callers should handle this exception appropriately based on operation criticality.
     */
    public static class RedisTimeoutException extends RuntimeException {
        private final String operation;
        private final String repository;

        public RedisTimeoutException(String operation, String repository) {
            super("Redis operation timeout: " + operation + " in " + repository);
            this.operation = operation;
            this.repository = repository;
        }

        /** Returns the name of the operation that timed out. */
        public String getOperation() {
            return operation;
        }

        /** Returns the repository where the timeout occurred. */
        public String getRepository() {
            return repository;
        }
    }
}
