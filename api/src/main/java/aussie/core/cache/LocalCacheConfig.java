package aussie.core.cache;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for local in-memory caches.
 *
 * <p>Configuration prefix: {@code aussie.cache.local}
 *
 * <p>These caches provide multi-instance safe caching with TTL-based
 * expiration. Lower TTL values provide faster cross-instance consistency
 * but increase storage load. Higher TTL values provide better performance
 * but slower propagation of changes.
 *
 * <h2>Configuration Properties</h2>
 * <ul>
 *   <li>{@code aussie.cache.local.service-routes-ttl} - TTL for service route cache</li>
 *   <li>{@code aussie.cache.local.rate-limit-config-ttl} - TTL for rate limit config cache</li>
 *   <li>{@code aussie.cache.local.max-entries} - Maximum entries per cache</li>
 *   <li>{@code aussie.cache.local.jitter-factor} - TTL jitter factor (0.0-0.5)</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_CACHE_LOCAL_SERVICE_ROUTES_TTL} - e.g., "PT30S" for 30 seconds</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_RATE_LIMIT_CONFIG_TTL} - e.g., "PT30S" for 30 seconds</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_MAX_ENTRIES} - e.g., "10000"</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_JITTER_FACTOR} - e.g., "0.1" for ±10% jitter</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.cache.local")
public interface LocalCacheConfig {

    /**
     * TTL for the service routes cache.
     *
     * <p>Controls how long compiled service routes are cached before
     * re-checking persistent storage. Lower values mean faster propagation
     * of service registration changes across instances.
     *
     * @return TTL duration (default: 30 seconds)
     */
    @WithDefault("PT30S")
    Duration serviceRoutesTtl();

    /**
     * TTL for the rate limit configuration cache.
     *
     * <p>Controls how long service rate limit configurations are cached
     * before re-checking persistent storage.
     *
     * @return TTL duration (default: 30 seconds)
     */
    @WithDefault("PT30S")
    Duration rateLimitConfigTtl();

    /**
     * TTL for the sampling configuration cache.
     *
     * <p>Controls how long service sampling configurations are cached
     * before re-checking persistent storage. This is the local in-memory
     * cache TTL; Redis cache TTL is configured separately.
     *
     * @return TTL duration (default: 30 seconds)
     */
    @WithDefault("PT30S")
    Duration samplingConfigTtl();

    /**
     * Maximum number of entries in local caches.
     *
     * <p>Prevents unbounded memory growth. When exceeded, least recently
     * used entries are evicted.
     *
     * @return maximum entries (default: 10000)
     */
    @WithDefault("10000")
    long maxEntries();

    /**
     * TTL jitter factor for cache entries.
     *
     * <p>To prevent cache refresh storms in multi-instance deployments,
     * each entry's TTL is varied by ±(jitterFactor * 100)%. For example,
     * a jitter factor of 0.1 means TTLs vary by ±10%.
     *
     * <p>This prevents all instances from refreshing their caches at the
     * same time, reducing load spikes on backend storage.
     *
     * <p>Set to 0 to disable jitter (not recommended for production).
     *
     * @return jitter factor between 0.0 and 0.5 (default: 0.1 = ±10%)
     */
    @WithDefault("0.1")
    double jitterFactor();
}
