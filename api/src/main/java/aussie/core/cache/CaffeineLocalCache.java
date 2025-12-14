package aussie.core.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Caffeine-backed local cache implementation with TTL support.
 *
 * <p>
 * Provides high-performance in-memory caching with automatic expiration.
 * Entries are automatically evicted after the configured TTL, ensuring
 * eventual consistency in multi-instance deployments.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class CaffeineLocalCache<K, V> implements LocalCache<K, V> {

    private final Cache<K, V> cache;

    /**
     * Creates a new Caffeine-backed cache.
     *
     * @param ttl     the time-to-live for cache entries
     * @param maxSize the maximum number of entries in the cache
     */
    public CaffeineLocalCache(Duration ttl, long maxSize) {
        this.cache =
                Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }

    @Override
    public void invalidate(K key) {
        cache.invalidate(key);
    }

    @Override
    public void invalidateAll() {
        cache.invalidateAll();
    }

    @Override
    public Collection<V> values() {
        return cache.asMap().values();
    }

    @Override
    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
