package aussie.core.port.out;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.Session;

/**
 * Outbound port for session storage operations.
 *
 * <p>This interface defines the contract for session persistence. Implementations
 * may store sessions in Redis, in-memory, or custom backends via the SPI.
 */
public interface SessionRepository {

    /**
     * Stores a new session only if the ID does not already exist.
     *
     * <p>This is the primary method for creating new sessions. It provides
     * atomic insert-if-absent semantics to prevent session ID collisions.
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>Redis: Use SETNX (SET if Not eXists)</li>
     *   <li>In-Memory: Use ConcurrentHashMap.putIfAbsent()</li>
     *   <li>Cassandra: Use lightweight transactions (IF NOT EXISTS)</li>
     * </ul>
     *
     * @param session Session to store
     * @return true if saved successfully, false if ID already exists
     */
    Uni<Boolean> saveIfAbsent(Session session);

    /**
     * Stores or replaces a session.
     *
     * <p>Use this for updates only, not for initial creation. For creating
     * new sessions, use {@link #saveIfAbsent(Session)} to prevent collisions.
     *
     * @param session Session to store
     * @return The saved session
     */
    Uni<Session> save(Session session);

    /**
     * Retrieves a session by ID.
     *
     * @param sessionId Session identifier
     * @return The session, or empty if not found
     */
    Uni<Optional<Session>> findById(String sessionId);

    /**
     * Updates a session (e.g., refresh lastAccessedAt).
     *
     * @param session Session to update
     * @return The updated session
     */
    Uni<Session> update(Session session);

    /**
     * Deletes/invalidates a session.
     *
     * @param sessionId Session identifier
     * @return Uni completing when deleted
     */
    Uni<Void> delete(String sessionId);

    /**
     * Deletes all sessions for a user.
     *
     * @param userId User identifier
     * @return Uni completing when all sessions are deleted
     */
    Uni<Void> deleteByUserId(String userId);

    /**
     * Checks if a session exists.
     *
     * @param sessionId Session identifier
     * @return true if the session exists
     */
    Uni<Boolean> exists(String sessionId);
}
