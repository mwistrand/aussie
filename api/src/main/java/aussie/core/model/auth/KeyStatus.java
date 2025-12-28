package aussie.core.model.auth;

/**
 * Lifecycle status of a signing key.
 *
 * <p>Keys transition through these states:
 * <pre>
 * PENDING → ACTIVE → DEPRECATED → RETIRED
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING} - Created but not yet active (grace period)</li>
 *   <li>{@link #ACTIVE} - Used for signing and verification</li>
 *   <li>{@link #DEPRECATED} - Verification only, no longer used for signing</li>
 *   <li>{@link #RETIRED} - No longer used, can be deleted</li>
 * </ul>
 */
public enum KeyStatus {

    /**
     * Key is created but not yet active.
     * Used during grace period before a new key becomes the primary signing key.
     */
    PENDING,

    /**
     * Key is actively used for signing new tokens and verifying existing ones.
     * Only one key should be ACTIVE at any time.
     */
    ACTIVE,

    /**
     * Key is no longer used for signing but still valid for verification.
     * Tokens signed with this key remain valid until they expire.
     */
    DEPRECATED,

    /**
     * Key is fully retired and should not be used for any purpose.
     * Can be safely deleted from storage.
     */
    RETIRED
}
