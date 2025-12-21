package aussie.core.port.out;

import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * SPI for token revocation storage.
 *
 * <p>Platform teams can provide custom implementations for their preferred
 * storage backend (e.g., Memcached via AWS ElastiCache, DynamoDB, etc.).
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Entries MUST expire automatically based on the provided TTL</li>
 *   <li>All operations MUST be non-blocking (return Uni/Multi)</li>
 *   <li>Implementations SHOULD be thread-safe</li>
 *   <li>Implementations SHOULD handle connection failures gracefully</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Platform teams register custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class MemcachedTokenRevocationRepository implements TokenRevocationRepository {
 *     // Custom implementation using AWS ElastiCache Memcached
 * }
 * }</pre>
 *
 * @see aussie.adapter.out.storage.redis.RedisTokenRevocationRepository
 */
public interface TokenRevocationRepository {

    /**
     * Revoke a specific token by its JTI.
     *
     * <p>The revocation entry should automatically expire at the specified time.
     * This typically matches the token's original expiry time, ensuring that
     * revocation entries don't accumulate indefinitely.
     *
     * @param jti       the JWT ID to revoke
     * @param expiresAt when the revocation entry should expire (matches token expiry)
     * @return Uni completing when revocation is stored
     */
    Uni<Void> revoke(String jti, Instant expiresAt);

    /**
     * Check if a token has been revoked.
     *
     * @param jti the JWT ID to check
     * @return Uni with true if revoked, false otherwise
     */
    Uni<Boolean> isRevoked(String jti);

    /**
     * Revoke all tokens for a user issued before a given timestamp.
     *
     * <p>This supports "logout everywhere" functionality. Any token issued
     * to this user before the specified timestamp should be considered revoked.
     *
     * @param userId       the user whose tokens should be revoked
     * @param issuedBefore tokens issued before this time are considered revoked
     * @param expiresAt    when this user-level revocation entry should expire
     * @return Uni completing when revocation is stored
     */
    Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt);

    /**
     * Check if a user has a blanket revocation affecting tokens issued before a time.
     *
     * @param userId   the user to check
     * @param issuedAt when the token being validated was issued
     * @return Uni with true if user has revocation affecting this token
     */
    Uni<Boolean> isUserRevoked(String userId, Instant issuedAt);

    /**
     * Stream all currently revoked JTIs (for bloom filter rebuild).
     *
     * <p>This method is called periodically to rebuild the bloom filter.
     * Implementations should stream results efficiently to avoid memory pressure.
     *
     * @return Multi streaming all revoked JTIs
     */
    Multi<String> streamAllRevokedJtis();

    /**
     * Stream all users with blanket revocations (for bloom filter rebuild).
     *
     * @return Multi streaming user IDs with active revocations
     */
    Multi<String> streamAllRevokedUsers();
}
