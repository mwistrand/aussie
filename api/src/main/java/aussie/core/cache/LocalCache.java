package aussie.core.cache;

import java.util.Collection;
import java.util.Optional;

/**
 * Local in-memory cache interface with TTL support.
 *
 * <p>
 * Provides a generic caching abstraction for storing and retrieving values
 * with automatic expiration. Used for multi-instance safe caching where
 * TTL ensures eventual consistency across instances.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface LocalCache<K, V> {

    /**
     * Gets a value from the cache.
     *
     * @param key the cache key
     * @return Optional containing the value if present and not expired
     */
    Optional<V> get(K key);

    /**
     * Puts a value into the cache.
     *
     * <p>
     * The value will be automatically evicted after the configured TTL.
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    void put(K key, V value);

    /**
     * Invalidates (removes) a specific cache entry.
     *
     * @param key the cache key to invalidate
     */
    void invalidate(K key);

    /**
     * Invalidates all entries in the cache.
     */
    void invalidateAll();

    /**
     * Returns a snapshot of all values currently in the cache.
     *
     * <p>
     * Note: This is a snapshot and may not reflect concurrent modifications.
     *
     * @return collection of all cached values
     */
    Collection<V> values();

    /**
     * Returns the estimated number of entries in the cache.
     *
     * @return estimated entry count
     */
    long estimatedSize();
}
