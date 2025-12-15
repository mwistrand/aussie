package aussie.core.model.session;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Represents an authenticated user session.
 *
 * <p>Sessions are created when users authenticate through an organization's
 * identity provider. The session is stored server-side and a session cookie
 * is sent to the client.
 *
 * @param id Unique session identifier (cryptographically secure)
 * @param userId User identifier from the auth provider
 * @param issuer Auth provider issuer (e.g., OIDC issuer URL)
 * @param claims Additional claims from the auth token
 * @param permissions User permissions/roles
 * @param createdAt Session creation timestamp
 * @param expiresAt Session expiration timestamp
 * @param lastAccessedAt Last activity timestamp (for idle timeout)
 * @param userAgent Client user agent (optional, for audit)
 * @param ipAddress Client IP address (optional, for audit)
 */
public record Session(
        String id,
        String userId,
        String issuer,
        Map<String, Object> claims,
        Set<String> permissions,
        Instant createdAt,
        Instant expiresAt,
        Instant lastAccessedAt,
        String userAgent,
        String ipAddress) {

    /**
     * Creates a new session with updated lastAccessedAt timestamp.
     */
    public Session withLastAccessedAt(Instant lastAccessedAt) {
        return new Session(
                id, userId, issuer, claims, permissions, createdAt, expiresAt, lastAccessedAt, userAgent, ipAddress);
    }

    /**
     * Creates a new session with updated expiration time.
     */
    public Session withExpiresAt(Instant expiresAt) {
        return new Session(
                id, userId, issuer, claims, permissions, createdAt, expiresAt, lastAccessedAt, userAgent, ipAddress);
    }

    /**
     * Creates a new session with a different ID.
     */
    public Session withId(String id) {
        return new Session(
                id, userId, issuer, claims, permissions, createdAt, expiresAt, lastAccessedAt, userAgent, ipAddress);
    }

    /**
     * Checks if the session has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if the session has exceeded the idle timeout.
     *
     * @param idleTimeout Maximum duration of inactivity
     */
    public boolean isIdle(java.time.Duration idleTimeout) {
        if (lastAccessedAt == null || idleTimeout == null) {
            return false;
        }
        return Instant.now().isAfter(lastAccessedAt.plus(idleTimeout));
    }

    /**
     * Checks if the session is valid (not expired and not idle).
     *
     * @param idleTimeout Maximum duration of inactivity (null to skip idle check)
     */
    public boolean isValid(java.time.Duration idleTimeout) {
        return !isExpired() && (idleTimeout == null || !isIdle(idleTimeout));
    }
}
