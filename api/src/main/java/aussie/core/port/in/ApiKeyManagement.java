package aussie.core.port.in;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import aussie.core.model.ApiKey;
import aussie.core.model.ApiKeyCreateResult;

/**
 * Port for managing API keys used for authentication.
 *
 * <p>API keys are stored persistently and can be created, validated, listed,
 * and revoked at runtime without restarting the gateway.
 */
public interface ApiKeyManagement {

    /**
     * Creates a new API key.
     *
     * <p>The plaintext key is returned only once in the result. After creation,
     * only the hash is stored and the key cannot be retrieved.
     *
     * @param name        display name for the key (e.g., "user-service-prod")
     * @param description optional description of the key's purpose
     * @param permissions set of permissions to grant (e.g., "admin:read", "admin:write")
     * @param ttl         time-to-live for the key (null = never expires)
     * @return result containing the key ID, plaintext key, and metadata
     */
    ApiKeyCreateResult create(String name, String description, Set<String> permissions, Duration ttl);

    /**
     * Validates a plaintext API key and returns the associated metadata if valid.
     *
     * <p>A key is valid if:
     * <ul>
     *   <li>It exists in storage</li>
     *   <li>It has not been revoked</li>
     *   <li>It has not expired</li>
     * </ul>
     *
     * @param plaintextKey the API key to validate
     * @return the ApiKey metadata if valid, empty if invalid or not found
     */
    Optional<ApiKey> validate(String plaintextKey);

    /**
     * Lists all API keys with hashes redacted.
     *
     * @return list of all keys (including revoked/expired) with redacted hashes
     */
    List<ApiKey> list();

    /**
     * Revokes an API key by its ID.
     *
     * <p>Revoked keys cannot be used for authentication. The key record is
     * retained for audit purposes but marked as revoked.
     *
     * @param keyId the key ID to revoke
     * @return true if the key was found and revoked, false if not found
     */
    boolean revoke(String keyId);

    /**
     * Gets a specific API key by ID with hash redacted.
     *
     * @param keyId the key ID to retrieve
     * @return the ApiKey if found, empty otherwise
     */
    Optional<ApiKey> get(String keyId);
}
