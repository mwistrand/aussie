package aussie.core.port.out;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

/**
 * Repository for storing OIDC refresh tokens.
 *
 * <p>Refresh tokens are stored keyed by session ID, allowing token renewal
 * for active sessions. Implementations must support automatic TTL expiration.
 *
 * <p>Implementations must ensure:
 * <ul>
 *   <li>Secure storage (tokens are sensitive credentials)</li>
 *   <li>Automatic expiration based on TTL</li>
 *   <li>Atomic operations where applicable</li>
 * </ul>
 */
public interface OidcRefreshTokenRepository {

    /**
     * Store a refresh token for a session.
     *
     * <p>If a refresh token already exists for the session, it is replaced.
     *
     * @param sessionId The session ID to associate with the token
     * @param refreshToken The refresh token from the IdP
     * @param ttl Time-to-live for the stored token
     * @return Uni completing when stored
     */
    Uni<Void> store(String sessionId, String refreshToken, Duration ttl);

    /**
     * Retrieve the refresh token for a session.
     *
     * @param sessionId The session ID
     * @return The refresh token, or empty if not found or expired
     */
    Uni<Optional<String>> get(String sessionId);

    /**
     * Delete the refresh token for a session.
     *
     * <p>Called when a session is invalidated or logged out.
     *
     * @param sessionId The session ID
     * @return Uni completing when deleted
     */
    Uni<Void> delete(String sessionId);
}
