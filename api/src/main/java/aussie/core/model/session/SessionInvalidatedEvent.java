package aussie.core.model.session;

import java.util.Optional;

/**
 * Event fired when a user session is invalidated.
 *
 * <p>This event is used to notify other components (e.g., WebSocket gateway)
 * that they should close any connections associated with this session.
 *
 * @param sessionId the invalidated session ID (always present for single session logout)
 * @param userId    the user ID (present for "logout everywhere" operations)
 */
public record SessionInvalidatedEvent(Optional<String> sessionId, Optional<String> userId) {

    /**
     * Create an event for a single session invalidation.
     */
    public static SessionInvalidatedEvent forSession(String sessionId) {
        return new SessionInvalidatedEvent(Optional.of(sessionId), Optional.empty());
    }

    /**
     * Create an event for invalidating all sessions of a user.
     */
    public static SessionInvalidatedEvent forUser(String userId) {
        return new SessionInvalidatedEvent(Optional.empty(), Optional.of(userId));
    }

    /**
     * Check if a given session ID matches this event.
     *
     * @param targetSessionId the session ID to check
     * @param targetUserId    the user ID associated with the session
     * @return true if this event applies to the given session
     */
    public boolean appliesTo(String targetSessionId, String targetUserId) {
        // Match by specific session ID
        if (sessionId.isPresent() && sessionId.get().equals(targetSessionId)) {
            return true;
        }
        // Match by user ID (logout everywhere)
        return userId.isPresent() && userId.get().equals(targetUserId);
    }
}
