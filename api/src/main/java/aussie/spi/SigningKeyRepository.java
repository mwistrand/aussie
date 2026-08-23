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

    /** Whether key state is durable and shared across gateway instances. */
    default boolean isDurable() {
        return false;
    }

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

    /** Atomically store a new pending key only when its ID and the pending slot are unused. */
    Uni<Boolean> storePendingIfAbsent(SigningKeyRecord key);

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
     * Get all keys that must be published (PENDING + ACTIVE + DEPRECATED).
     *
     * <p>Pending keys are published during their grace period but cannot sign or
     * verify tokens until activation.
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
     * Atomically replace the expected active key with a pending key.
     *
     * <p>The repository must perform the comparison, old-key deprecation, and
     * new-key activation in one transaction. Returning {@code false} reports a
     * concurrent activation or stale expectation; no key state may change.
     *
     * @param keyId pending key to activate
     * @param expectedActiveKeyId active key observed by the caller, if any
     * @param transitionTime time applied to both state transitions
     * @return whether the compare-and-set succeeded
     */
    Uni<Boolean> activate(String keyId, Optional<String> expectedActiveKeyId, Instant transitionTime);

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
