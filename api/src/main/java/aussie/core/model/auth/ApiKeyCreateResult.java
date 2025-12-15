package aussie.core.model.auth;

/**
 * Result of creating a new API key.
 *
 * <p>This is the only time the plaintext key is available. After creation,
 * only the hash is stored and the key cannot be retrieved.
 *
 * @param keyId        the short identifier for this key
 * @param plaintextKey the actual API key (only returned once!)
 * @param metadata     the stored key metadata (with hash, not plaintext)
 */
public record ApiKeyCreateResult(String keyId, String plaintextKey, ApiKey metadata) {

    public ApiKeyCreateResult {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new IllegalArgumentException("Plaintext key cannot be null or blank");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
    }
}
