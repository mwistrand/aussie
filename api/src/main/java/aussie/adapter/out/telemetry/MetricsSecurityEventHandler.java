package aussie.adapter.out.telemetry;

import java.util.Set;

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

    private static final Set<String> AUTH_REASONS = Set.of(
            "invalid_key",
            "invalid_api_key_format",
            "invalid_authorization",
            "invalid_session",
            "expired_session",
            "invalid_token",
            "ambiguous_credential",
            "conflicting_authentication",
            "other");
    private static final Set<String> AUTH_METHODS =
            Set.of("api_key", "authorization", "bearer", "jwt", "multiple", "oidc", "session", "unknown");
    private static final Set<String> ACCESS_REASONS =
            Set.of("ip_blocked", "visibility_private", "network_policy_denied", "forbidden", "other");
    private static final Set<String> PATTERNS =
            Set.of("brute_force", "brute_force_attempt", "high_error_rate", "request_spike", "other");
    private static final Set<String> ATTACKS = Set.of("request_flood", "slowloris", "other");
    private static final Set<String> INVALIDATION_REASONS = Set.of("logout", "timeout", "forced", "other");

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
                .tag("reason", known(event.reason(), AUTH_REASONS))
                .tag("method", known(event.attemptedMethod(), AUTH_METHODS))
                .register(registry)
                .increment();
    }

    private void recordAuthLockout(SecurityEvent.AuthenticationLockout event) {
        Counter.builder("aussie.security.auth.lockouts")
                .description("Authentication lockouts (brute force protection)")
                .tag("key_type", extractKeyType(event.lockedKey()))
                .register(registry)
                .increment();
    }

    private String extractKeyType(String lockedKey) {
        if (lockedKey == null) {
            return "unknown";
        }
        final var colonIndex = lockedKey.indexOf(':');
        return switch (colonIndex > 0 ? lockedKey.substring(0, colonIndex) : "") {
            case "ip", "user", "apikey" -> lockedKey.substring(0, colonIndex);
            default -> "unknown";
        };
    }

    private void recordAccessDenied(SecurityEvent.AccessDenied event) {
        Counter.builder("aussie.security.access.denied")
                .description("Access denied events")
                .tag("service_id", nullSafe(event.serviceId()))
                .tag("reason", known(event.reason(), ACCESS_REASONS))
                .register(registry)
                .increment();
    }

    private void recordRateLimitExceeded(SecurityEvent.RateLimitExceeded event) {
        Counter.builder("aussie.security.rate_limit.exceeded")
                .description("Rate limit violations")
                .tag("service_id", nullSafe(event.serviceId()))
                .register(registry)
                .increment();
    }

    private void recordSuspiciousPattern(SecurityEvent.SuspiciousPattern event) {
        Counter.builder("aussie.security.suspicious.patterns")
                .description("Suspicious traffic patterns detected")
                .tag("pattern_type", known(event.patternType(), PATTERNS))
                .register(registry)
                .increment();
    }

    private void recordDosAttack(SecurityEvent.DosAttackDetected event) {
        Counter.builder("aussie.security.dos.detected")
                .description("DoS attacks detected")
                .tag("attack_type", known(event.attackType(), ATTACKS))
                .register(registry)
                .increment();
    }

    private void recordSessionInvalidated(SecurityEvent.SessionInvalidated event) {
        Counter.builder("aussie.security.session.invalidated")
                .description("Session invalidations")
                .tag("reason", known(event.reason(), INVALIDATION_REASONS))
                .register(registry)
                .increment();
    }

    private String known(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "other";
    }

    private String nullSafe(String value) {
        return value != null ? value : "unknown";
    }
}
