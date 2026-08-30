package aussie.adapter.out.telemetry;

import java.util.Set;

import org.jboss.logging.Logger;

import aussie.spi.SecurityEvent;
import aussie.spi.SecurityEventHandler;

/**
 * Security event handler that logs events using JBoss Logging.
 *
 * <p>This is a built-in handler with priority 0 that always runs.
 * Log levels are based on event severity:
 * <ul>
 *   <li>INFO severity → DEBUG level</li>
 *   <li>WARNING severity → WARN level</li>
 *   <li>CRITICAL severity → ERROR level</li>
 * </ul>
 */
public class LoggingSecurityEventHandler implements SecurityEventHandler {

    private static final Logger LOG = Logger.getLogger("aussie.security");
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
    private static final Set<String> SOURCES = Set.of("forwarded", "socket", "unknown");
    private static final Set<String> PATTERNS =
            Set.of("brute_force", "brute_force_attempt", "high_error_rate", "request_spike", "other");
    private static final Set<String> ATTACKS = Set.of("request_flood", "slowloris", "other");
    private static final Set<String> INVALIDATION_REASONS = Set.of("logout", "timeout", "forced", "other");

    @Override
    public String name() {
        return "logging";
    }

    @Override
    public String description() {
        return "Logs security events using JBoss Logging";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public void handle(SecurityEvent event) {
        var message = formatEvent(event);
        var severity = event.severity();

        switch (severity) {
            case INFO -> LOG.debug(message);
            case WARNING -> LOG.warn(message);
            case CRITICAL -> LOG.error(message);
        }
    }

    String formatEvent(SecurityEvent event) {
        return switch (event) {
            case SecurityEvent.AuthenticationFailure e ->
                String.format(
                        "AUTH_FAILURE: client_present=%s reason=%s method=%s failures=%d",
                        present(e.clientIdentifier()),
                        known(e.reason(), AUTH_REASONS),
                        known(e.attemptedMethod(), AUTH_METHODS),
                        e.failureCount());

            case SecurityEvent.AuthenticationLockout e ->
                String.format(
                        "AUTH_LOCKOUT: client_present=%s key_type=%s attempts=%d duration=%ds lockout_count=%d",
                        present(e.clientIdentifier()),
                        keyType(e.lockedKey()),
                        e.failedAttempts(),
                        e.lockoutDurationSeconds(),
                        e.lockoutCount());

            case SecurityEvent.AccessDenied e ->
                String.format(
                        "ACCESS_DENIED: client_present=%s source=%s direct_peer_present=%s trust_path_present=%s service_present=%s route_present=%s reason=%s policy_version=%d",
                        present(e.clientIdentifier()),
                        known(e.source(), SOURCES),
                        present(e.directPeerIdentifier()),
                        present(e.trustPath()),
                        present(e.serviceId()),
                        present(e.path()),
                        known(e.reason(), ACCESS_REASONS),
                        e.policyVersion());

            case SecurityEvent.RateLimitExceeded e ->
                String.format(
                        "RATE_LIMIT: client_present=%s service_present=%s requests=%d threshold=%d window=%ds",
                        present(e.clientIdentifier()),
                        present(e.serviceId()),
                        e.requestCount(),
                        e.threshold(),
                        e.windowSeconds());

            case SecurityEvent.SuspiciousPattern e ->
                String.format(
                        "SUSPICIOUS: client_present=%s type=%s confidence=%.2f details_present=%s",
                        present(e.clientIdentifier()),
                        known(e.patternType(), PATTERNS),
                        e.confidenceScore(),
                        present(e.details()));

            case SecurityEvent.DosAttackDetected e ->
                String.format(
                        "DOS_ATTACK: client_present=%s type=%s evidence_present=%s",
                        present(e.clientIdentifier()),
                        known(e.attackType(), ATTACKS),
                        e.evidence() != null && !e.evidence().isEmpty());

            case SecurityEvent.SessionInvalidated e ->
                String.format(
                        "SESSION_INVALIDATED: client_present=%s session_present=%s user_present=%s reason=%s",
                        present(e.clientIdentifier()),
                        present(e.sessionId()),
                        present(e.userId()),
                        known(e.reason(), INVALIDATION_REASONS));
        };
    }

    private static String known(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "other";
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String keyType(String key) {
        if (key == null) {
            return "unknown";
        }
        final var colon = key.indexOf(':');
        return switch (colon > 0 ? key.substring(0, colon) : "") {
            case "ip", "user", "apikey" -> key.substring(0, colon);
            default -> "unknown";
        };
    }
}
