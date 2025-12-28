package aussie.spi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;

/**
 * SPI for signing key storage and retrieval.
 *
 * <p>Platform teams can provide custom implementations for their preferred
 * key management solution (e.g., HashiCorp Vault, AWS KMS, Azure Key Vault,
 * database-backed, HSM).
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Private keys MUST be stored securely (encrypted at rest)</li>
 *   <li>Key lifecycle transitions MUST be atomic</li>
 *   <li>Implementations SHOULD support concurrent access</li>
 *   <li>All operations MUST be non-blocking (return Uni)</li>
 * </ul>
 *
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>Private keys should never be logged</li>
 *   <li>Consider using HSM for production deployments</li>
 *   <li>Implement proper access controls on the underlying storage</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Platform teams register custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class VaultSigningKeyRepository implements SigningKeyRepository {
 *     // Custom implementation using HashiCorp Vault
 * }
 * }</pre>
 *
 * @see aussie.adapter.out.auth.ConfigSigningKeyRepository
 */
public interface SigningKeyRepository {

    /**
     * Store a new signing key.
     *
     * <p>The key should be stored with its current status. If a key with
     * the same ID already exists, the behavior is implementation-defined
     * (typically fails or updates).
     *
     * @param key The signing key with metadata
     * @return Uni completing when stored
     */
    Uni<Void> store(SigningKeyRecord key);

    /**
     * Get a key by its ID.
     *
     * @param keyId The key identifier
     * @return Uni with the key if found, empty otherwise
     */
    Uni<Optional<SigningKeyRecord>> findById(String keyId);

    /**
     * Get the current active signing key.
     *
     * <p>There should be at most one ACTIVE key at any time.
     * If multiple keys are somehow ACTIVE, the implementation
     * should return the most recently activated one.
     *
     * @return Uni with the active key, or empty if none active
     */
    Uni<Optional<SigningKeyRecord>> findActive();

    /**
     * Get all keys valid for verification (ACTIVE + DEPRECATED).
     *
     * <p>These keys should be included in the JWKS endpoint
     * so that tokens signed with any of them can be verified.
     *
     * @return Uni with list of verification keys (may be empty)
     */
    Uni<List<SigningKeyRecord>> findAllForVerification();

    /**
     * Get all keys with the specified status.
     *
     * @param status The status to filter by
     * @return Uni with list of keys with the given status
     */
    Uni<List<SigningKeyRecord>> findByStatus(KeyStatus status);

    /**
     * Update a key's status.
     *
     * <p>Status transitions should follow the lifecycle:
     * PENDING → ACTIVE → DEPRECATED → RETIRED
     *
     * @param keyId The key identifier
     * @param newStatus The new status
     * @param transitionTime When the transition occurred
     * @return Uni completing when updated
     */
    Uni<Void> updateStatus(String keyId, KeyStatus newStatus, Instant transitionTime);

    /**
     * Delete a key from storage.
     *
     * <p>This should only be called for RETIRED keys that are
     * past the retention period. Deleting active or deprecated
     * keys could cause token validation failures.
     *
     * @param keyId The key identifier
     * @return Uni completing when deleted
     */
    Uni<Void> delete(String keyId);

    /**
     * Get all keys (for administrative purposes).
     *
     * @return Uni with list of all keys
     */
    Uni<List<SigningKeyRecord>> findAll();
}
