package aussie.adapter.out.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import aussie.spi.SecurityEvent;
import aussie.spi.SecurityEventHandler;

/**
 * Security event handler that records events as Micrometer metrics.
 *
 * <p>This is a built-in handler with priority 10 that records security
 * events to the metrics registry for monitoring and alerting.
 *
 * <p>Metrics recorded:
 * <ul>
 *   <li>{@code aussie.security.events.total} - Total events by type and severity</li>
 *   <li>{@code aussie.security.auth.failures} - Authentication failures by reason</li>
 *   <li>{@code aussie.security.rate_limit.exceeded} - Rate limit violations</li>
 *   <li>{@code aussie.security.dos.detected} - DoS attack detections</li>
 * </ul>
 */
public class MetricsSecurityEventHandler implements SecurityEventHandler {

    private MeterRegistry registry;

    public MetricsSecurityEventHandler() {
        // Default constructor for ServiceLoader
    }

    public MetricsSecurityEventHandler(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Set the meter registry.
     *
     * <p>Called by the dispatcher after ServiceLoader instantiation.
     *
     * @param registry the Micrometer registry
     */
    public void setMeterRegistry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public String description() {
        return "Records security events as Micrometer metrics";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        return registry != null;
    }

    @Override
    public void handle(SecurityEvent event) {
        if (registry == null) {
            return;
        }

        // Record generic event counter
        recordEventCounter(event);

        // Record type-specific metrics
        switch (event) {
            case SecurityEvent.AuthenticationFailure e -> recordAuthFailure(e);
            case SecurityEvent.AuthenticationLockout e -> recordAuthLockout(e);
            case SecurityEvent.AccessDenied e -> recordAccessDenied(e);
            case SecurityEvent.RateLimitExceeded e -> recordRateLimitExceeded(e);
            case SecurityEvent.SuspiciousPattern e -> recordSuspiciousPattern(e);
            case SecurityEvent.DosAttackDetected e -> recordDosAttack(e);
            case SecurityEvent.SessionInvalidated e -> recordSessionInvalidated(e);
        }
    }

    private void recordEventCounter(SecurityEvent event) {
        Counter.builder("aussie.security.events.total")
                .description("Total security events")
                .tag("event_type", event.getClass().getSimpleName())
                .tag("severity", event.severity().name().toLowerCase())
                .register(registry)
                .increment();
    }

    private void recordAuthFailure(SecurityEvent.AuthenticationFailure event) {
        Counter.builder("aussie.security.auth.failures")
                .description("Authentication failures")
                .tag("reason", event.reason())
                .tag("method", event.attemptedMethod())
                .tag("client_ip_hash", event.clientIdentifier())
                .register(registry)
                .increment();
    }

    private void recordAuthLockout(SecurityEvent.AuthenticationLockout event) {
        Counter.builder("aussie.security.auth.lockouts")
                .description("Authentication lockouts (brute force protection)")
                .tag("key_type", extractKeyType(event.lockedKey()))
                .tag("client_ip_hash", event.clientIdentifier())
                .register(registry)
                .increment();
    }

    private String extractKeyType(String lockedKey) {
        if (lockedKey == null) {
            return "unknown";
        }
        int colonIndex = lockedKey.indexOf(':');
        return colonIndex > 0 ? lockedKey.substring(0, colonIndex) : "unknown";
    }

    private void recordAccessDenied(SecurityEvent.AccessDenied event) {
        Counter.builder("aussie.security.access.denied")
                .description("Access denied events")
                .tag("service_id", nullSafe(event.serviceId()))
                .tag("reason", event.reason())
                .register(registry)
                .increment();
    }

    private void recordRateLimitExceeded(SecurityEvent.RateLimitExceeded event) {
        Counter.builder("aussie.security.rate_limit.exceeded")
                .description("Rate limit violations")
                .tag("service_id", nullSafe(event.serviceId()))
                .tag("client_ip_hash", event.clientIdentifier())
                .register(registry)
                .increment();
    }

    private void recordSuspiciousPattern(SecurityEvent.SuspiciousPattern event) {
        Counter.builder("aussie.security.suspicious.patterns")
                .description("Suspicious traffic patterns detected")
                .tag("pattern_type", event.patternType())
                .tag("client_ip_hash", event.clientIdentifier())
                .register(registry)
                .increment();
    }

    private void recordDosAttack(SecurityEvent.DosAttackDetected event) {
        Counter.builder("aussie.security.dos.detected")
                .description("DoS attacks detected")
                .tag("attack_type", event.attackType())
                .tag("client_ip_hash", event.clientIdentifier())
                .register(registry)
                .increment();
    }

    private void recordSessionInvalidated(SecurityEvent.SessionInvalidated event) {
        Counter.builder("aussie.security.session.invalidated")
                .description("Session invalidations")
                .tag("reason", event.reason())
                .register(registry)
                .increment();
    }

    private String nullSafe(String value) {
        return value != null ? value : "unknown";
    }
}
