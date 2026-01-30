package aussie.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration mapping for hierarchical OTel sampling.
 *
 * <p>Configuration prefix: {@code aussie.telemetry.sampling}
 *
 * <p>Platform teams configure sampling behavior at three levels:
 * <ol>
 *   <li>Platform defaults (this configuration)</li>
 *   <li>Service-level overrides (via service registration)</li>
 *   <li>Endpoint-level overrides (via endpoint configuration)</li>
 * </ol>
 *
 * <p>Sampling rates are expressed as values between 0.0 and 1.0, where:
 * <ul>
 *   <li>1.0 = no sampling (100% of requests are traced)</li>
 *   <li>0.5 = 50% of requests are traced</li>
 *   <li>0.0 = no requests are traced</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_TELEMETRY_SAMPLING_ENABLED} - Enable hierarchical sampling</li>
 *   <li>{@code AUSSIE_TELEMETRY_SAMPLING_DEFAULT_RATE} - Default sampling rate</li>
 *   <li>{@code AUSSIE_TELEMETRY_SAMPLING_MINIMUM_RATE} - Platform minimum (floor)</li>
 *   <li>{@code AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE} - Platform maximum (ceiling)</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.telemetry.sampling")
public interface SamplingConfig {

    /**
     * Enable hierarchical sampling.
     *
     * <p>When enabled, uses the custom HierarchicalSampler that applies
     * service and endpoint-specific sampling rates. When disabled, falls
     * back to the Quarkus default sampler configured via
     * {@code quarkus.otel.traces.sampler}.
     *
     * @return true if hierarchical sampling is enabled (default: false)
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Default sampling rate for all requests.
     *
     * <p>This rate applies when no service or endpoint-specific configuration
     * is defined. A value of 1.0 means all requests are traced (no sampling),
     * while 0.0 means no requests are traced.
     *
     * @return default sampling rate (default: 1.0, meaning no sampling)
     */
    @WithName("default-rate")
    @WithDefault("1.0")
    double defaultRate();

    /**
     * Minimum allowed sampling rate (floor).
     *
     * <p>Platform teams can set a floor to ensure critical traces are always
     * captured. Service and endpoint configurations cannot specify a rate
     * below this value.
     *
     * @return minimum sampling rate (default: 0.0)
     */
    @WithName("minimum-rate")
    @WithDefault("0.0")
    double minimumRate();

    /**
     * Maximum allowed sampling rate (ceiling).
     *
     * <p>Typically 1.0 to allow services to disable sampling entirely.
     * Platform teams can lower this to enforce some level of sampling
     * across all services.
     *
     * @return maximum sampling rate (default: 1.0)
     */
    @WithName("maximum-rate")
    @WithDefault("1.0")
    double maximumRate();

    /**
     * Redis cache configuration for sampling configs.
     */
    CacheConfig cache();

    /**
     * Lookup configuration for sampling config resolution.
     */
    LookupConfig lookup();

    /**
     * Cache configuration for sampling configs.
     */
    interface CacheConfig {

        /**
         * Enable Redis caching for sampling configurations.
         *
         * <p>When enabled, sampling configurations are cached in Redis
         * for cross-instance consistency with a longer TTL than the
         * local in-memory cache.
         *
         * @return true to use Redis caching (default: true)
         */
        @WithDefault("true")
        boolean redisEnabled();

        /**
         * TTL for Redis-cached sampling configurations.
         *
         * <p>Should be longer than the local cache TTL to reduce
         * Cassandra lookups while still providing reasonable freshness.
         *
         * @return Redis cache TTL in ISO-8601 duration format (default: PT5M)
         */
        @WithName("redis-ttl")
        @WithDefault("PT5M")
        java.time.Duration redisTtl();
    }

    /**
     * Lookup configuration for sampling config resolution.
     */
    interface LookupConfig {

        /**
         * Timeout for synchronous sampling config lookups.
         *
         * <p>This timeout applies when blocking lookups are unavoidable
         * (e.g., the blocking {@code resolveByServiceId} method). The
         * non-blocking method returns the platform default immediately
         * on cache miss and populates the cache asynchronously.
         *
         * <p>Environment variable: {@code AUSSIE_TELEMETRY_SAMPLING_LOOKUP_TIMEOUT}
         *
         * @return lookup timeout in ISO-8601 duration format (default: PT5S = 5 seconds)
         */
        @WithDefault("PT5S")
        java.time.Duration timeout();
    }
}
