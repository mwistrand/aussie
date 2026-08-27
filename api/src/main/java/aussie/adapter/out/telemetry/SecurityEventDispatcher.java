package aussie.adapter.out.telemetry;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import aussie.spi.SecurityEvent;
import aussie.spi.SecurityEventHandler;

/**
 * Dispatches security events to registered handlers.
 *
 * <p>Handlers are discovered via {@link ServiceLoader} and invoked in priority order
 * (highest priority first). Events are dispatched asynchronously to avoid blocking
 * request processing.
 *
 * <p>When security monitoring is disabled, events are silently dropped.
 */
@ApplicationScoped
public class SecurityEventDispatcher {

    private static final Logger LOG = Logger.getLogger(SecurityEventDispatcher.class);

    private final TelemetryConfig config;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final List<SecurityEventHandler> configuredHandlers;

    private List<SecurityEventHandler> handlers;
    private ExecutorService executor;
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @Inject
    public SecurityEventDispatcher(TelemetryConfig config, MeterRegistry meterRegistry) {
        this(config, meterRegistry, null);
    }

    SecurityEventDispatcher(
            TelemetryConfig config, MeterRegistry meterRegistry, List<SecurityEventHandler> configuredHandlers) {
        this.config = config;
        this.meterRegistry = meterRegistry;
        this.configuredHandlers = configuredHandlers;
        this.enabled = config != null && config.enabled() && config.security().enabled();
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            LOG.debug("Security monitoring is disabled - event dispatcher inactive");
            return;
        }
        final var securityConfig = config.security();
        if (securityConfig.eventQueueCapacity() < 1) {
            throw new IllegalStateException("aussie.telemetry.security.event-queue-capacity must be positive");
        }
        if (securityConfig.shutdownDrainTimeout() == null
                || securityConfig.shutdownDrainTimeout().isZero()
                || securityConfig.shutdownDrainTimeout().isNegative()) {
            throw new IllegalStateException("aussie.telemetry.security.shutdown-drain-timeout must be positive");
        }

        // Load handlers via ServiceLoader
        var loadedHandlers = configuredHandlers != null
                ? configuredHandlers
                : ServiceLoader.load(SecurityEventHandler.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();

        // Inject MeterRegistry into MetricsSecurityEventHandler
        for (var handler : loadedHandlers) {
            if (handler instanceof MetricsSecurityEventHandler metricsHandler) {
                metricsHandler.setMeterRegistry(meterRegistry);
            }
        }

        // Filter available and sort by priority
        handlers = loadedHandlers.stream()
                .filter(SecurityEventHandler::isAvailable)
                .sorted(Comparator.comparingInt(SecurityEventHandler::priority).reversed())
                .toList();

        if (handlers.isEmpty()) {
            LOG.warn("No security event handlers found - events will not be processed");
        } else {
            LOG.infof(
                    "Loaded %d security event handler(s): %s",
                    handlers.size(),
                    handlers.stream()
                            .map(h -> h.name() + "(priority=" + h.priority() + ")")
                            .toList());
        }

        executor = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(securityConfig.eventQueueCapacity()),
                runnable -> {
                    var thread = new Thread(runnable, "security-event-dispatcher");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        accepting.set(true);
    }

    @PreDestroy
    void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        accepting.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(
                        config.security().shutdownDrainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    increment("aussie.security.events.dispatch.shutdown_timeouts");
                    final var dropped = executor.shutdownNow();
                    if (!dropped.isEmpty()) {
                        increment("aussie.security.events.dispatch.forced_drops", dropped.size());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                increment("aussie.security.events.dispatch.shutdown_timeouts");
                final var dropped = executor.shutdownNow();
                if (!dropped.isEmpty()) {
                    increment("aussie.security.events.dispatch.forced_drops", dropped.size());
                }
            }
        }
        if (handlers != null) {
            handlers.forEach(handler -> {
                try {
                    handler.close();
                } catch (Exception e) {
                    LOG.warnf("Error closing handler %s: %s", handler.name(), e.getMessage());
                }
            });
        }
    }

    /**
     * Check if security event dispatching is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Dispatch a security event to all registered handlers.
     *
     * <p>Events are dispatched asynchronously. If security monitoring is disabled,
     * this method is a no-op.
     *
     * @param event the event to dispatch
     */
    public void dispatch(SecurityEvent event) {
        if (!enabled || handlers == null || handlers.isEmpty()) {
            return;
        }

        if (!accepting.get()) {
            increment("aussie.security.events.dispatch.rejected");
            return;
        }

        try {
            executor.execute(() -> {
                for (var handler : handlers) {
                    try {
                        handler.handle(event);
                    } catch (Exception e) {
                        LOG.warnf("Handler %s failed to process event: %s", handler.name(), e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            increment("aussie.security.events.dispatch.rejected");
        }
    }

    private void increment(String name) {
        increment(name, 1);
    }

    private void increment(String name, double amount) {
        if (meterRegistry != null) {
            meterRegistry.counter(name).increment(amount);
        }
    }

    /**
     * Get the list of registered handlers.
     *
     * @return list of handlers (empty if disabled)
     */
    public List<SecurityEventHandler> getHandlers() {
        return handlers != null ? handlers : List.of();
    }
}
