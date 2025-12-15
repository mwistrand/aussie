package aussie.spi;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed interface representing security events detected by the gateway.
 *
 * <p>Security events are dispatched to registered {@link aussie.spi.SecurityEventHandler}
 * implementations for alerting, logging, and metrics recording.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@link AuthenticationFailure} - Failed authentication attempt</li>
 *   <li>{@link AccessDenied} - Request blocked by access control</li>
 *   <li>{@link RateLimitExceeded} - Client exceeded rate limits</li>
 *   <li>{@link SuspiciousPattern} - Anomalous traffic pattern detected</li>
 *   <li>{@link DosAttackDetected} - Potential DoS attack detected</li>
 *   <li>{@link SessionInvalidated} - User session was invalidated</li>
 * </ul>
 */
public sealed interface SecurityEvent {

    /**
     * Return the timestamp when this event occurred.
     *
     * @return event timestamp
     */
    Instant timestamp();

    /**
     * Return the client identifier (usually hashed IP).
     *
     * @return client identifier
     */
    String clientIdentifier();

    /**
     * Return the severity level of this event.
     *
     * @return severity level
     */
    Severity severity();

    /**
     * Severity levels for security events.
     */
    enum Severity {
        /** Informational events (e.g., normal session invalidation). */
        INFO,
        /** Warning events requiring attention (e.g., repeated auth failures). */
        WARNING,
        /** Critical events requiring immediate action (e.g., DoS attack). */
        CRITICAL
    }

    /**
     * Authentication failure event.
     *
     * @param timestamp when the failure occurred
     * @param clientIdentifier hashed client IP
     * @param reason failure reason (e.g., "invalid_api_key", "expired_session")
     * @param attemptedMethod authentication method that was attempted
     * @param failureCount number of recent failures from this client
     */
    record AuthenticationFailure(
            Instant timestamp, String clientIdentifier, String reason, String attemptedMethod, int failureCount)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            return failureCount >= 5 ? Severity.WARNING : Severity.INFO;
        }
    }

    /**
     * Access denied event.
     *
     * @param timestamp when access was denied
     * @param clientIdentifier hashed client IP
     * @param serviceId the target service
     * @param path the requested path
     * @param reason denial reason (e.g., "ip_blocked", "visibility_private")
     */
    record AccessDenied(Instant timestamp, String clientIdentifier, String serviceId, String path, String reason)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            return Severity.INFO;
        }
    }

    /**
     * Rate limit exceeded event.
     *
     * @param timestamp when the limit was exceeded
     * @param clientIdentifier hashed client IP
     * @param serviceId the target service (may be null for global limits)
     * @param requestCount number of requests in the window
     * @param threshold the rate limit threshold
     * @param windowSeconds the rate limit window in seconds
     */
    record RateLimitExceeded(
            Instant timestamp,
            String clientIdentifier,
            String serviceId,
            int requestCount,
            int threshold,
            int windowSeconds)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            double ratio = (double) requestCount / threshold;
            if (ratio > 5.0) {
                return Severity.CRITICAL;
            } else if (ratio > 2.0) {
                return Severity.WARNING;
            }
            return Severity.INFO;
        }
    }

    /**
     * Suspicious traffic pattern event.
     *
     * @param timestamp when the pattern was detected
     * @param clientIdentifier hashed client IP
     * @param patternType the type of suspicious pattern
     * @param details human-readable details
     * @param confidenceScore confidence in detection (0.0 to 1.0)
     */
    record SuspiciousPattern(
            Instant timestamp, String clientIdentifier, String patternType, String details, double confidenceScore)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            if (confidenceScore > 0.9) {
                return Severity.CRITICAL;
            } else if (confidenceScore > 0.7) {
                return Severity.WARNING;
            }
            return Severity.INFO;
        }
    }

    /**
     * DoS attack detected event.
     *
     * @param timestamp when the attack was detected
     * @param clientIdentifier hashed client IP or "distributed" for distributed attacks
     * @param attackType the type of attack (e.g., "request_flood", "slowloris")
     * @param evidence evidence supporting the detection
     */
    record DosAttackDetected(
            Instant timestamp, String clientIdentifier, String attackType, Map<String, Object> evidence)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            return Severity.CRITICAL;
        }
    }

    /**
     * Session invalidated event.
     *
     * @param timestamp when the session was invalidated
     * @param clientIdentifier hashed client IP
     * @param sessionId the invalidated session ID (hashed)
     * @param userId the user whose session was invalidated
     * @param reason invalidation reason (e.g., "logout", "timeout", "forced")
     */
    record SessionInvalidated(
            Instant timestamp, String clientIdentifier, String sessionId, String userId, String reason)
            implements SecurityEvent {

        @Override
        public Severity severity() {
            return "forced".equals(reason) ? Severity.WARNING : Severity.INFO;
        }
    }
}
