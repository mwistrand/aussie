package aussie.core.port.out;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

/**
 * SPI for PKCE challenge storage.
 *
 * <p>Platform teams can provide custom implementations for their preferred
 * storage backend. Challenges are short-lived (typically 10 minutes) and
 * must be deleted after successful verification.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Challenges MUST expire automatically based on configured TTL</li>
 *   <li>Challenges MUST be deleted after successful verification (one-time use)</li>
 *   <li>All operations MUST be non-blocking (return Uni)</li>
 * </ul>
 */
public interface PkceChallengeRepository {

    /**
     * Store a PKCE challenge for later verification.
     *
     * @param state The OAuth state parameter (used as key)
     * @param challenge The code_challenge value
     * @param ttl How long the challenge should be valid
     * @return Uni completing when stored
     */
    Uni<Void> store(String state, String challenge, Duration ttl);

    /**
     * Retrieve and delete a challenge (one-time use).
     *
     * <p>This operation MUST be atomic: if the challenge exists, it should
     * be returned and immediately deleted to prevent replay attacks.
     *
     * @param state The OAuth state parameter
     * @return Uni with the challenge if found, empty if not found or expired
     */
    Uni<Optional<String>> consumeChallenge(String state);
}
