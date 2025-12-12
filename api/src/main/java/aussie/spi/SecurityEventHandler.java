package aussie.spi;

/**
 * SPI for handling security events detected by the gateway.
 *
 * <p>Platform teams can implement this interface to integrate with their
 * alerting and monitoring systems. Implementations are discovered via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Built-in handlers:
 * <ul>
 *   <li>{@code logging} - Logs events using JBoss Logging (priority 0)</li>
 *   <li>{@code metrics} - Records events as Micrometer metrics (priority 10)</li>
 * </ul>
 *
 * <p>Example implementation:
 * <pre>{@code
 * public class PagerDutySecurityEventHandler implements SecurityEventHandler {
 *     @Override
 *     public String name() { return "pagerduty"; }
 *
 *     @Override
 *     public int priority() { return 100; }
 *
 *     @Override
 *     public void handle(SecurityEvent event) {
 *         if (event instanceof SecurityEvent.DosAttackDetected dos) {
 *             pagerDutyClient.trigger(createIncident(dos));
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Register implementations in:
 * {@code META-INF/services/aussie.spi.SecurityEventHandler}
 */
public interface SecurityEventHandler {

    /**
     * Returns the unique name of this handler.
     *
     * @return handler name (e.g., "pagerduty", "slack", "webhook")
     */
    String name();

    /**
     * Returns a human-readable description of this handler.
     *
     * @return handler description
     */
    default String description() {
        return name() + " security event handler";
    }

    /**
     * Returns the priority of this handler.
     *
     * <p>Higher priority handlers are invoked first. Built-in handlers use:
     * <ul>
     *   <li>0 - Logging handler (always runs)</li>
     *   <li>10 - Metrics handler</li>
     * </ul>
     *
     * <p>Custom handlers should use priorities above 50 if they need
     * to process events before built-in handlers.
     *
     * @return priority value (higher = invoked first)
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns whether this handler is currently available.
     *
     * <p>Handlers should return false if their external dependencies
     * (e.g., alerting service, webhook endpoint) are not configured
     * or not reachable.
     *
     * @return true if handler is available and should receive events
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Handle a security event.
     *
     * <p>Implementations should handle events asynchronously if they
     * perform I/O operations (e.g., sending alerts) to avoid blocking
     * the request processing thread.
     *
     * <p>Implementations should catch and log any exceptions rather
     * than propagating them, as this would prevent other handlers
     * from processing the event.
     *
     * @param event the security event to handle
     */
    void handle(SecurityEvent event);

    /**
     * Called during shutdown to release any resources.
     *
     * <p>Default implementation does nothing.
     */
    default void close() {}
}
