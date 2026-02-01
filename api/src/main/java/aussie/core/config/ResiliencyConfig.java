package aussie.core.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for resiliency settings including timeouts and cache limits.
 *
 * <p>Configuration prefix: {@code aussie.resiliency}
 *
 * <p>This configuration controls:
 * <ul>
 *   <li>HTTP proxy connect and request timeouts</li>
 *   <li>JWKS fetch timeouts and cache limits</li>
 *   <li>Cassandra query timeouts</li>
 *   <li>Redis operation timeouts</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.resiliency")
public interface ResiliencyConfig {

    /**
     * HTTP proxy timeout configuration.
     */
    HttpConfig http();

    /**
     * JWKS fetch and cache configuration.
     */
    JwksConfig jwks();

    /**
     * Cassandra timeout configuration.
     */
    CassandraConfig cassandra();

    /**
     * Redis timeout configuration.
     */
    RedisConfig redis();

    /**
     * HTTP proxy timeout settings.
     */
    interface HttpConfig {

        /**
         * Maximum time to wait for a response from the upstream service.
         *
         * <p>If exceeded, returns 504 Gateway Timeout.
         *
         * @return Request timeout duration (default: 30 seconds)
         */
        @WithDefault("PT30S")
        Duration requestTimeout();

        /**
         * Maximum time to establish a TCP connection to the upstream service.
         *
         * @return Connect timeout duration (default: 5 seconds)
         */
        @WithDefault("PT5S")
        Duration connectTimeout();

        /**
         * Maximum HTTP connections per upstream host (bulkhead).
         *
         * @return Max connections per host (default: 50)
         */
        @WithDefault("50")
        int maxConnectionsPerHost();

        /**
         * Maximum total HTTP connections across all upstream hosts (bulkhead).
         *
         * @return Max total connections (default: 200)
         */
        @WithDefault("200")
        int maxConnections();
    }

    /**
     * JWKS (JSON Web Key Set) configuration.
     */
    interface JwksConfig {

        /**
         * Maximum time to wait when fetching JWKS from an identity provider.
         *
         * <p>If exceeded, falls back to cached keys if available.
         *
         * @return Fetch timeout duration (default: 5 seconds)
         */
        @WithDefault("PT5S")
        Duration fetchTimeout();

        /**
         * Maximum number of JWKS entries to cache.
         *
         * <p>Each unique JWKS URI (identity provider) consumes one entry.
         * Uses LRU eviction when limit is exceeded.
         *
         * @return Maximum cache entries (default: 100)
         */
        @WithDefault("100")
        int maxCacheEntries();

        /**
         * Time-to-live for cached JWKS entries.
         *
         * <p>Entries are refreshed when accessed after TTL expires.
         *
         * @return Cache TTL duration (default: 1 hour)
         */
        @WithDefault("PT1H")
        Duration cacheTtl();

        /**
         * Maximum concurrent JWKS fetch connections (bulkhead).
         *
         * @return Max concurrent connections (default: 10)
         */
        @WithDefault("10")
        int maxConnections();
    }

    /**
     * Cassandra timeout settings.
     */
    interface CassandraConfig {

        /**
         * Maximum time to wait for a Cassandra query to complete.
         *
         * <p>Applied at the driver level to all queries.
         *
         * @return Query timeout duration (default: 5 seconds)
         */
        @WithDefault("PT5S")
        Duration queryTimeout();

        /**
         * Connections per node in local datacenter (bulkhead).
         *
         * @return Pool size per local node (default: 30)
         */
        @WithDefault("30")
        int poolLocalSize();

        /**
         * Maximum concurrent requests per Cassandra connection.
         *
         * @return Max requests per connection (default: 1024)
         */
        @WithDefault("1024")
        int maxRequestsPerConnection();
    }

    /**
     * Redis timeout settings.
     */
    interface RedisConfig {

        /**
         * Maximum time to wait for a Redis operation to complete.
         *
         * <p>Behavior on timeout depends on operation type:
         * <ul>
         *   <li>Session operations: propagate error (critical)</li>
         *   <li>Cache reads: return empty (treat as cache miss)</li>
         *   <li>Rate limiting: allow request (fail-open)</li>
         *   <li>Token revocation: deny request (fail-closed for security)</li>
         * </ul>
         *
         * @return Operation timeout duration (default: 1 second)
         */
        @WithDefault("PT1S")
        Duration operationTimeout();

        /**
         * Maximum Redis connections in pool (bulkhead).
         *
         * @return Pool size (default: 30)
         */
        @WithDefault("30")
        int poolSize();

        /**
         * Maximum requests waiting when Redis pool is exhausted.
         *
         * @return Max waiting requests (default: 100)
         */
        @WithDefault("100")
        int poolWaiting();
    }
}
