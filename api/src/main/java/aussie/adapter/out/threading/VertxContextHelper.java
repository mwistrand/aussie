package aussie.adapter.out.threading;

import java.util.concurrent.Executor;

import io.quarkus.runtime.LaunchMode;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;
import org.jboss.logging.Logger;

/**
 * Utility for managing Vert.x event loop context in async operations.
 *
 * <p>In Aussie's threading model:
 * <ul>
 *   <li>The Vert.x event loop handles ALL CPU-bound work</li>
 *   <li>IO operations are non-blocking (Cassandra, Redis, HTTP use NIO)</li>
 *   <li>When IO completes, callbacks must return to the event loop for CPU work</li>
 * </ul>
 *
 * <p>This helper ensures that async operation results are emitted on the Vert.x
 * event loop context, not on driver-internal threads (like Cassandra's Netty IO threads).
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Cassandra operation - results come back on Netty thread, emit to event loop
 * return Uni.createFrom()
 *     .completionStage(() -> session.executeAsync(query).toCompletableFuture())
 *     .emitOn(VertxContextHelper.eventLoopExecutor())
 *     .map(this::processResult);
 *
 * // Or capture context before async boundary
 * var executor = VertxContextHelper.captureContext();
 * return externalAsyncCall()
 *     .emitOn(executor)
 *     .map(this::processCpuIntensive);
 * }</pre>
 *
 * <p>Note: {@link #emitOnEventLoop(Uni)} uses {@code emitOn} which changes the executor
 * for downstream stages while preserving ordering per Mutiny semantics. Long-running
 * stages may still block the event loop if not properly offloaded.
 *
 * @see io.vertx.core.Context
 */
public final class VertxContextHelper {

    private static final Logger LOG = Logger.getLogger(VertxContextHelper.class);

    private VertxContextHelper() {
        // Utility class
    }

    /**
     * Returns an executor that runs tasks on the current Vert.x event loop context.
     *
     * <p>If called from a Vert.x context (e.g., during request handling), returns
     * an executor that dispatches to that context. If no Vert.x context is available
     * (e.g., during testing or startup), falls back to the default worker pool with
     * a warning log.
     *
     * <p><b>Warning:</b> The fallback to worker pool may violate the "CPU-only on event loop"
     * rule. This fallback exists for testing and startup scenarios. In production request
     * handling, a Vert.x context should always be available.
     *
     * @return an executor bound to the current Vert.x context, or the default worker pool
     */
    public static Executor eventLoopExecutor() {
        final var context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        LOG.warn("No Vert.x context available; falling back to worker pool. "
                + "This may indicate a threading issue if called during request handling.");
        return Infrastructure.getDefaultWorkerPool();
    }

    /**
     * Captures the current Vert.x context and returns an executor for later use.
     *
     * <p>Use this when you need to capture the context before crossing an async
     * boundary where the context might not be available.
     *
     * @return an executor bound to the current Vert.x context
     * @throws IllegalStateException if called outside a Vert.x context
     */
    public static Executor captureContext() {
        final var context = Vertx.currentContext();
        if (context == null) {
            throw new IllegalStateException(
                    "No Vert.x context available. This method must be called from a Vert.x thread.");
        }
        return command -> context.runOnContext(v -> command.run());
    }

    /**
     * Wraps a Uni to ensure its result is emitted on the Vert.x event loop.
     *
     * <p>Captures the current context and ensures the Uni's result (and any
     * subsequent transformations) execute on the event loop.
     *
     * @param uni the Uni to wrap
     * @param <T> the result type
     * @return a Uni that emits on the Vert.x event loop
     */
    public static <T> Uni<T> emitOnEventLoop(Uni<T> uni) {
        return uni.emitOn(eventLoopExecutor());
    }

    /**
     * Checks if the current thread is running on a Vert.x event loop context.
     *
     * <p>Useful for assertions and debugging to verify code is running
     * on the expected thread.
     *
     * @return true if running on a Vert.x event loop context
     */
    public static boolean isOnEventLoop() {
        final var context = Vertx.currentContext();
        return context != null && context.isEventLoopContext();
    }

    /**
     * Asserts that the current code is running on the Vert.x event loop.
     *
     * <p>In dev/test mode, throws an exception if not on the event loop.
     * In production mode, logs a warning instead to avoid crashing.
     *
     * <p>Use this during development to catch threading bugs early.
     *
     * @throws IllegalStateException if not on event loop and running in dev/test mode
     */
    public static void assertOnEventLoop() {
        if (!isOnEventLoop()) {
            final var message = "Expected to be on Vert.x event loop but was on: "
                    + Thread.currentThread().getName();
            if (LaunchMode.current() == LaunchMode.NORMAL) {
                // Production: log warning but don't crash
                LOG.warn(message);
            } else {
                // Dev/Test: throw to catch bugs early
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * Checks if running on event loop and logs a warning if not.
     *
     * <p>Unlike {@link #assertOnEventLoop()}, this method never throws.
     * Use this for non-critical checks where you want visibility but not failure.
     *
     * @return true if on event loop, false otherwise (with warning logged)
     */
    public static boolean checkOnEventLoop() {
        if (!isOnEventLoop()) {
            LOG.warnv(
                    "Expected to be on Vert.x event loop but was on: {0}",
                    Thread.currentThread().getName());
            return false;
        }
        return true;
    }
}
