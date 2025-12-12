package aussie.adapter.out.telemetry;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import aussie.config.TelemetryConfigMapping;
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

    private final TelemetryConfigMapping config;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    private List<SecurityEventHandler> handlers;
    private ExecutorService executor;

    @Inject
    public SecurityEventDispatcher(TelemetryConfigMapping config, MeterRegistry meterRegistry) {
        this.config = config;
        this.meterRegistry = meterRegistry;
        this.enabled = config != null && config.enabled() && config.security().enabled();
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            LOG.debug("Security monitoring is disabled - event dispatcher inactive");
            return;
        }

        // Load handlers via ServiceLoader
        var loadedHandlers = ServiceLoader.load(SecurityEventHandler.class).stream()
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

        // Create executor for async dispatch
        executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "security-event-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
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

        executor.submit(() -> {
            for (var handler : handlers) {
                try {
                    handler.handle(event);
                } catch (Exception e) {
                    LOG.warnf("Handler %s failed to process event: %s", handler.name(), e.getMessage());
                }
            }
        });
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
