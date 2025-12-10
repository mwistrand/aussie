package aussie.telemetry.security;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed interface representing security events detected by the gateway.
 *
 * <p>These events are recorded as metrics and can trigger alerts when
 * thresholds are exceeded.
 */
public sealed interface SecurityEvent {

    /**
     * Returns the timestamp when the event occurred.
     *
     * @return event timestamp
     */
    Instant timestamp();

    /**
     * Returns the client IP address associated with the event.
     *
     * @return client IP address
     */
    String clientIp();

    /**
     * Authentication failure event.
     *
     * @param timestamp when the failure occurred
     * @param clientIp client IP address
     * @param reason failure reason (invalid_key, expired, revoked, etc.)
     * @param attemptedCredential redacted credential identifier
     * @param failureCount number of recent failures from this client
     */
    record AuthenticationFailure(
            Instant timestamp, String clientIp, String reason, String attemptedCredential, int failureCount)
            implements SecurityEvent {}

    /**
     * Access denied event.
     *
     * @param timestamp when the denial occurred
     * @param clientIp client IP address
     * @param serviceId target service identifier
     * @param path requested path
     * @param reason denial reason (ip_blocked, domain_blocked, visibility, etc.)
     */
    record AccessDenied(Instant timestamp, String clientIp, String serviceId, String path, String reason)
            implements SecurityEvent {}

    /**
     * Rate limit exceeded event.
     *
     * @param timestamp when the threshold was exceeded
     * @param clientIp client IP address
     * @param serviceId target service (null for global limits)
     * @param requestCount number of requests in the window
     * @param threshold configured threshold
     */
    record RateLimitExceeded(Instant timestamp, String clientIp, String serviceId, int requestCount, int threshold)
            implements SecurityEvent {}

    /**
     * Suspicious activity pattern detected.
     *
     * @param timestamp when the pattern was detected
     * @param clientIp client IP address
     * @param patternType type of pattern (brute_force, high_error_rate, etc.)
     * @param details human-readable description
     * @param confidenceScore confidence score (0.0 to 1.0)
     */
    record SuspiciousPattern(
            Instant timestamp, String clientIp, String patternType, String details, double confidenceScore)
            implements SecurityEvent {}

    /**
     * DoS attack detected.
     *
     * @param timestamp when the attack was detected
     * @param clientIp source IP address
     * @param attackType type of attack (request_flood, slowloris, etc.)
     * @param evidence supporting evidence as key-value pairs
     */
    record DosAttackDetected(Instant timestamp, String clientIp, String attackType, Map<String, Object> evidence)
            implements SecurityEvent {}
}
