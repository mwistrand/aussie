package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.ApiKey;
import aussie.core.model.service.ConditionalWriteResult;

/**
 * Port interface for persistent storage of API keys.
 *
 * <p>This is the storage abstraction for API key management. All implementations
 * must provide durable persistence—data must survive application restarts.
 */
public interface ApiKeyRepository {

    /**
     * Save or update an API key.
     *
     * @param apiKey the API key to persist
     * @return Uni completing when save is durable
     */
    Uni<Void> save(ApiKey apiKey);

    Uni<ConditionalWriteResult> replaceIfVersion(ApiKey apiKey, long expectedVersion);

    /**
     * Find an API key by its unique identifier.
     *
     * @param keyId the key identifier
     * @return Uni with Optional containing the key if found
     */
    Uni<Optional<ApiKey>> findById(String keyId);

    /**
     * Find an API key by its hash.
     *
     * <p>Used during authentication to validate a plaintext key.
     *
     * @param keyHash the SHA-256 hash of the key
     * @return Uni with Optional containing the key if found
     */
    Uni<Optional<ApiKey>> findByHash(String keyHash);

    /**
     * Delete an API key.
     *
     * @param keyId the key identifier to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String keyId);

    Uni<ConditionalWriteResult> deleteIfVersion(String keyId, long expectedVersion);

    /**
     * Retrieve all API keys.
     *
     * @return Uni with list of all keys
     */
    Uni<List<ApiKey>> findAll();

    /** Return one bounded administrative page. */
    default Uni<List<ApiKey>> findPage(int limit, int offset) {
        return findAll().map(values -> values.stream().skip(offset).limit(limit).toList());
    }

    /**
     * Check if a key exists.
     *
     * @param keyId the key identifier
     * @return Uni with true if exists
     */
    Uni<Boolean> exists(String keyId);
}
