package aussie.core.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

/**
 * Caffeine-backed local cache implementation with TTL support and jitter.
 *
 * <p>
 * Provides high-performance in-memory caching with automatic expiration.
 * Entries are automatically evicted after the configured TTL, ensuring
 * eventual consistency in multi-instance deployments.
 *
 * <p>
 * <b>TTL Jitter:</b> To prevent cache refresh storms in multi-instance deployments,
 * each entry's TTL is varied by a configurable jitter factor. This prevents all
 * instances from refreshing their caches at the same time, reducing load spikes
 * on backend storage.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class CaffeineLocalCache<K, V> implements LocalCache<K, V> {

    private static final double DEFAULT_JITTER_FACTOR = 0.1; // ±10% jitter

    private final Cache<K, V> cache;
    private final long baseTtlNanos;
    private final double jitterFactor;

    /**
     * Create a new Caffeine-backed cache with default TTL jitter (±10%).
     *
     * @param ttl     the base time-to-live for cache entries
     * @param maxSize the maximum number of entries in the cache
     */
    public CaffeineLocalCache(Duration ttl, long maxSize) {
        this(ttl, maxSize, DEFAULT_JITTER_FACTOR);
    }

    /**
     * Create a new Caffeine-backed cache with configurable TTL jitter.
     *
     * @param ttl          the base time-to-live for cache entries
     * @param maxSize      the maximum number of entries in the cache
     * @param jitterFactor the jitter factor (0.0 to 0.5). A value of 0.1 means ±10% jitter.
     *                     Set to 0 to disable jitter.
     */
    public CaffeineLocalCache(Duration ttl, long maxSize, double jitterFactor) {
        if (jitterFactor < 0.0 || jitterFactor > 0.5) {
            throw new IllegalArgumentException("Jitter factor must be between 0.0 and 0.5, got: " + jitterFactor);
        }
        this.baseTtlNanos = ttl.toNanos();
        this.jitterFactor = jitterFactor;

        if (jitterFactor == 0.0) {
            // No jitter - use simple expireAfterWrite
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(maxSize)
                    .build();
        } else {
            // Use custom expiry with jitter
            this.cache = Caffeine.newBuilder()
                    .expireAfter(new JitteredExpiry())
                    .maximumSize(maxSize)
                    .build();
        }
    }

    /**
     * Expiry policy that adds random jitter to the TTL to prevent refresh storms.
     */
    private class JitteredExpiry implements Expiry<K, V> {
        @Override
        public long expireAfterCreate(K key, V value, long currentTime) {
            return applyJitter(baseTtlNanos);
        }

        @Override
        public long expireAfterUpdate(K key, V value, long currentTime, long currentDuration) {
            return applyJitter(baseTtlNanos);
        }

        @Override
        public long expireAfterRead(K key, V value, long currentTime, long currentDuration) {
            return currentDuration; // Don't change TTL on read
        }

        private long applyJitter(long baseTtl) {
            // Apply jitter: multiply by random value in [1-jitterFactor, 1+jitterFactor]
            final var jitter = ThreadLocalRandom.current().nextDouble() * 2 * jitterFactor;
            final var jitterMultiplier = 1.0 - jitterFactor + jitter;
            return (long) (baseTtl * jitterMultiplier);
        }
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
