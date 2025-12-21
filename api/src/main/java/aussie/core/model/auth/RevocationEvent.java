package aussie.core.model.auth;

import java.time.Instant;

/**
 * Event representing a token revocation for multi-instance synchronization.
 *
 * <p>These events are published via pub/sub to keep bloom filters synchronized
 * across all Aussie instances.
 */
public sealed interface RevocationEvent {

    /**
     * A specific token (JTI) was revoked.
     *
     * @param jti       the revoked JWT ID
     * @param expiresAt when the revocation expires
     */
    record JtiRevoked(String jti, Instant expiresAt) implements RevocationEvent {}

    /**
     * All tokens for a user issued before a timestamp were revoked.
     *
     * @param userId       the user whose tokens were revoked
     * @param issuedBefore tokens issued before this time are revoked
     * @param expiresAt    when the revocation expires
     */
    record UserRevoked(String userId, Instant issuedBefore, Instant expiresAt) implements RevocationEvent {}
}
