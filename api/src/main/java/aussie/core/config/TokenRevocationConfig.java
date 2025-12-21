package aussie.core.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for token revocation.
 *
 * <p>Configuration prefix: {@code aussie.auth.revocation}
 *
 * <p>Token revocation uses a tiered caching strategy for performance:
 * <ol>
 *   <li>Bloom filter - O(1) "definitely not revoked" check (~100ns)</li>
 *   <li>Local cache - LRU cache for confirmed revocations (~1μs)</li>
 *   <li>Remote store - Redis/SPI backend lookup (~1-5ms)</li>
 * </ol>
 */
@ConfigMapping(prefix = "aussie.auth.revocation")
public interface TokenRevocationConfig {

    /**
     * Enable token revocation checks.
     *
     * @return true if revocation is enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Enable user-level revocation checks.
     *
     * <p>When enabled, tokens can be revoked for all tokens issued
     * to a user before a specific timestamp (e.g., logout-everywhere).
     *
     * @return true if user revocation is enabled (default: true)
     */
    @WithDefault("true")
    boolean checkUserRevocation();

    /**
     * Skip revocation check for tokens expiring within this threshold.
     *
     * <p>Tokens with remaining TTL below this value skip revocation checks
     * entirely, as they will expire soon anyway. This optimization reduces
     * load on the revocation infrastructure.
     *
     * <p>Set to PT0S to always check (not recommended for high-traffic).
     *
     * @return threshold duration (default: 30 seconds)
     */
    @WithDefault("PT30S")
    Duration checkThreshold();

    /**
     * Bloom filter configuration.
     */
    BloomFilterConfig bloomFilter();

    /**
     * Local cache configuration.
     */
    CacheConfig cache();

    /**
     * Pub/sub configuration for multi-instance synchronization.
     */
    PubSubConfig pubsub();

    /**
     * Bloom filter configuration for fast negative lookups.
     *
     * <p>The bloom filter provides O(1) "definitely not revoked" checks
     * for the vast majority of requests, avoiding expensive remote lookups.
     */
    interface BloomFilterConfig {

        /**
         * Enable bloom filter optimization.
         *
         * @return true if bloom filter is enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Expected number of revoked tokens to store.
         *
         * <p>Size the bloom filter appropriately for your workload:
         * <ul>
         *   <li>Small (&lt;1K RPS): 100,000 (~1.2 MB)</li>
         *   <li>Medium (1-10K RPS): 1,000,000 (~12 MB)</li>
         *   <li>Large (&gt;10K RPS): 10,000,000 (~120 MB)</li>
         * </ul>
         *
         * @return expected insertions (default: 100,000)
         */
        @WithDefault("100000")
        int expectedInsertions();

        /**
         * Desired false positive probability.
         *
         * <p>Trade-offs:
         * <ul>
         *   <li>0.1% (0.001): ~10 bits/element, minimal false lookups</li>
         *   <li>1% (0.01): ~7 bits/element, 10x more false lookups but 30% less memory</li>
         *   <li>0.01% (0.0001): ~13 bits/element, negligible false lookups but 30% more memory</li>
         * </ul>
         *
         * @return false positive probability (default: 0.001 = 0.1%)
         */
        @WithDefault("0.001")
        double falsePositiveProbability();

        /**
         * Interval for full bloom filter rebuild from remote store.
         *
         * @return rebuild interval (default: 1 hour)
         */
        @WithDefault("PT1H")
        Duration rebuildInterval();
    }

    /**
     * Local LRU cache configuration for confirmed revocations.
     */
    interface CacheConfig {

        /**
         * Enable local cache.
         *
         * @return true if cache is enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Maximum number of entries in the cache.
         *
         * @return max size (default: 10,000)
         */
        @WithDefault("10000")
        int maxSize();

        /**
         * Cache entry TTL.
         *
         * @return TTL duration (default: 5 minutes)
         */
        @WithDefault("PT5M")
        Duration ttl();
    }

    /**
     * Pub/sub configuration for multi-instance bloom filter sync.
     */
    interface PubSubConfig {

        /**
         * Enable pub/sub for revocation events.
         *
         * <p>When enabled, revocation events are published to other
         * Aussie instances to keep bloom filters synchronized.
         *
         * @return true if pub/sub is enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Redis channel for revocation events.
         *
         * @return channel name (default: aussie:revocation:events)
         */
        @WithDefault("aussie:revocation:events")
        String channel();
    }
}
