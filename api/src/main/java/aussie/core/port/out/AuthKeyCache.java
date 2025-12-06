package aussie.core.port.out;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ApiKey;

/**
 * Cache interface for API key authentication lookups.
 *
 * <p>This interface provides a caching layer for API key validation to reduce
 * database lookups during authentication. Implementations should be configured
 * with appropriate TTL values to balance performance with security.
 *
 * <p>Cache entries are keyed by API key hash, allowing validation without
 * exposing plaintext keys in the cache.
 */
public interface AuthKeyCache {

    /**
     * Get a cached API key by its hash.
     *
     * @param keyHash the SHA-256 hash of the API key
     * @return Uni with Optional containing the cached key if present
     */
    Uni<Optional<ApiKey>> get(String keyHash);

    /**
     * Put an API key in the cache.
     *
     * <p>The cache implementation should use the configured TTL for expiration.
     *
     * @param keyHash the SHA-256 hash of the API key
     * @param apiKey the API key to cache
     * @return Uni completing when the operation finishes
     */
    Uni<Void> put(String keyHash, ApiKey apiKey);

    /**
     * Invalidate a cached API key.
     *
     * <p>Called when an API key is revoked or updated to ensure the cache
     * doesn't serve stale data.
     *
     * @param keyHash the SHA-256 hash of the API key to invalidate
     * @return Uni completing when invalidation finishes
     */
    Uni<Void> invalidate(String keyHash);

    /**
     * Invalidate all cached API keys.
     *
     * <p>Called during administrative operations that affect multiple keys.
     *
     * @return Uni completing when all entries are invalidated
     */
    Uni<Void> invalidateAll();
}
