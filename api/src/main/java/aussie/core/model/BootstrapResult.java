package aussie.core.model;

import java.time.Instant;

/**
 * Result of a successful bootstrap operation.
 *
 * <p>The bootstrap key's plaintext is never included in this result since
 * it was provided by the administrator and should not be logged or exposed.
 *
 * @param keyId       the ID of the created bootstrap key
 * @param expiresAt   when the bootstrap key expires
 * @param wasRecovery true if this was a recovery mode bootstrap
 */
public record BootstrapResult(String keyId, Instant expiresAt, boolean wasRecovery) {

    public BootstrapResult {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Bootstrap keys must have an expiration");
        }
    }

    /**
     * Create a result for a standard (non-recovery) bootstrap.
     */
    public static BootstrapResult standard(String keyId, Instant expiresAt) {
        return new BootstrapResult(keyId, expiresAt, false);
    }

    /**
     * Create a result for a recovery mode bootstrap.
     */
    public static BootstrapResult recovery(String keyId, Instant expiresAt) {
        return new BootstrapResult(keyId, expiresAt, true);
    }
}
