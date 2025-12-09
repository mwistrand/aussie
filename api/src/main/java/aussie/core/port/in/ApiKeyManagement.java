package aussie.core.port.in;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;

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
     * @param createdBy   identifier of the principal creating this key (e.g., key ID or "bootstrap")
     * @return Uni with result containing the key ID, plaintext key, and metadata
     */
    Uni<ApiKeyCreateResult> create(
            String name, String description, Set<String> permissions, Duration ttl, String createdBy);

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
     * @return Uni with the ApiKey metadata if valid, empty if invalid or not found
     */
    Uni<Optional<ApiKey>> validate(String plaintextKey);

    /**
     * Lists all API keys with hashes redacted.
     *
     * @return Uni with list of all keys (including revoked/expired) with redacted hashes
     */
    Uni<List<ApiKey>> list();

    /**
     * Revokes an API key by its ID.
     *
     * <p>Revoked keys cannot be used for authentication. The key record is
     * retained for audit purposes but marked as revoked.
     *
     * @param keyId the key ID to revoke
     * @return Uni with true if the key was found and revoked, false if not found
     */
    Uni<Boolean> revoke(String keyId);

    /**
     * Gets a specific API key by ID with hash redacted.
     *
     * @param keyId the key ID to retrieve
     * @return Uni with the ApiKey if found, empty otherwise
     */
    Uni<Optional<ApiKey>> get(String keyId);

    /**
     * Creates a new API key with a specific plaintext key value.
     *
     * <p>This method is used for bootstrap scenarios where the key is provided
     * by the administrator via configuration rather than auto-generated.
     *
     * @param name         display name for the key
     * @param description  optional description of the key's purpose
     * @param permissions  set of permissions to grant
     * @param ttl          time-to-live for the key (null = never expires)
     * @param plaintextKey the specific key value to use (min 32 chars)
     * @param createdBy    identifier of the principal creating this key (e.g., "bootstrap")
     * @return Uni with result containing the key ID and metadata
     * @throws IllegalArgumentException if the key is too short or TTL exceeds max
     */
    Uni<ApiKeyCreateResult> createWithKey(
            String name,
            String description,
            Set<String> permissions,
            Duration ttl,
            String plaintextKey,
            String createdBy);
}
