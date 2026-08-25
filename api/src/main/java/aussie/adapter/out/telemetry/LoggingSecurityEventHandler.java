package aussie.adapter.out.telemetry;

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
            case SecurityEvent.AuthenticationFailure e -> String.format(
                    "AUTH_FAILURE: client=%s reason=%s method=%s failures=%d",
                    e.clientIdentifier(), e.reason(), e.attemptedMethod(), e.failureCount());

            case SecurityEvent.AuthenticationLockout e -> String.format(
                    "AUTH_LOCKOUT: client=%s key_type=%s attempts=%d duration=%ds lockout_count=%d",
                    e.clientIdentifier(),
                    keyType(e.lockedKey()),
                    e.failedAttempts(),
                    e.lockoutDurationSeconds(),
                    e.lockoutCount());

            case SecurityEvent.AccessDenied e -> String.format(
                    "ACCESS_DENIED: client=%s source=%s direct_peer=%s trust_path=%s service=%s route=%s reason=%s policy_version=%d",
                    e.clientIdentifier(),
                    e.source(),
                    e.directPeerIdentifier(),
                    e.trustPath(),
                    e.serviceId(),
                    e.path(),
                    e.reason(),
                    e.policyVersion());

            case SecurityEvent.RateLimitExceeded e -> String.format(
                    "RATE_LIMIT: client=%s service=%s requests=%d threshold=%d window=%ds",
                    e.clientIdentifier(), e.serviceId(), e.requestCount(), e.threshold(), e.windowSeconds());

            case SecurityEvent.SuspiciousPattern e -> String.format(
                    "SUSPICIOUS: client=%s type=%s confidence=%.2f details_present=%s",
                    e.clientIdentifier(), e.patternType(), e.confidenceScore(), e.details() != null);

            case SecurityEvent.DosAttackDetected e -> String.format(
                    "DOS_ATTACK: client=%s type=%s evidence_present=%s",
                    e.clientIdentifier(),
                    e.attackType(),
                    e.evidence() != null && !e.evidence().isEmpty());

            case SecurityEvent.SessionInvalidated e -> String.format(
                    "SESSION_INVALIDATED: client=%s session_present=%s user_present=%s reason=%s",
                    e.clientIdentifier(), e.sessionId() != null, e.userId() != null, e.reason());
        };
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
