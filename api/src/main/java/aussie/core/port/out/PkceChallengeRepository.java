package aussie.core.port.out;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.OidcAuthorizationTransaction;

/**
 * SPI for one-time OIDC authorization transaction storage.
 *
 * <p>Platform teams can provide custom implementations for their preferred
 * storage backend. Transactions are short-lived (typically 10 minutes) and
 * must be deleted after successful verification.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Transactions MUST expire automatically based on configured TTL</li>
 *   <li>Transactions MUST be deleted when retrieved (one-time use)</li>
 *   <li>All operations MUST be non-blocking (return Uni)</li>
 * </ul>
 */
public interface PkceChallengeRepository {

    /**
     * Store an OIDC authorization transaction for later verification.
     *
     * @param state The OAuth state parameter (used as key)
     * @param transaction The complete server-owned transaction
     * @param ttl How long the challenge should be valid
     * @return Uni completing when stored
     */
    Uni<Void> store(String state, OidcAuthorizationTransaction transaction, Duration ttl);

    /**
     * Retrieve and delete a transaction (one-time use).
     *
     * <p>This operation MUST be atomic: if the transaction exists, it should
     * be returned and immediately deleted to prevent replay attacks.
     *
     * @param state The OAuth state parameter
     * @return Uni with the transaction if found, empty if not found or expired
     */
    Uni<Optional<OidcAuthorizationTransaction>> consume(String state);
}
