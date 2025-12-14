package aussie.config;

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
     * Maximum number of entries in local caches.
     *
     * <p>Prevents unbounded memory growth. When exceeded, least recently
     * used entries are evicted.
     *
     * @return maximum entries (default: 10000)
     */
    @WithDefault("10000")
    long maxEntries();
}
