package aussie.core.port.in;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;

import aussie.core.model.session.Session;

/**
 * Inbound port for session management operations.
 *
 * <p>This interface defines the primary operations for managing user sessions
 * in the Aussie gateway. Sessions are created after successful authentication
 * with an organization's identity provider.
 */
public interface SessionManagement {

    /**
     * Creates a new session for an authenticated user.
     *
     * <p>This method generates a unique session ID with collision detection
     * and retry logic. If a collision is detected, it will retry up to the
     * configured maximum attempts.
     *
     * @param userId User identifier from the auth provider
     * @param issuer Auth provider issuer
     * @param claims Additional claims from the auth token
     * @param permissions User permissions/roles
     * @param userAgent Client user agent (optional)
     * @param ipAddress Client IP address (optional)
     * @return The created session
     * @throws SessionCreationException if session creation fails after max retries
     */
    Uni<Session> createSession(
            String userId,
            String issuer,
            Map<String, Object> claims,
            Set<String> permissions,
            String userAgent,
            String ipAddress);

    /**
     * Retrieves and validates a session by ID.
     *
     * <p>Returns empty if the session doesn't exist, is expired, or has
     * exceeded the idle timeout.
     *
     * @param sessionId Session identifier
     * @return The valid session, or empty if not found or invalid
     */
    Uni<Optional<Session>> getSession(String sessionId);

    /**
     * Refreshes a session's expiration and last accessed time.
     *
     * <p>This implements sliding expiration - the session TTL is extended
     * on each access if sliding expiration is enabled.
     *
     * @param sessionId Session identifier
     * @return The refreshed session, or empty if not found
     */
    Uni<Optional<Session>> refreshSession(String sessionId);

    /**
     * Invalidates a single session.
     *
     * @param sessionId Session identifier
     * @return Uni completing when the session is invalidated
     */
    Uni<Void> invalidateSession(String sessionId);

    /**
     * Invalidates all sessions for a user (logout everywhere).
     *
     * @param userId User identifier
     * @return Uni completing when all sessions are invalidated
     */
    Uni<Void> invalidateAllUserSessions(String userId);

    /**
     * Exception thrown when session creation fails.
     */
    class SessionCreationException extends RuntimeException {
        public SessionCreationException(String message) {
            super(message);
        }

        public SessionCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
