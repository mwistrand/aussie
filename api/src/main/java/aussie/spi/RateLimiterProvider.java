package aussie.spi;

import aussie.core.port.out.RateLimiter;

/**
 * Service Provider Interface for rate limiter implementations.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} and
 * selected based on priority. Higher priority providers are preferred.
 *
 * <p>Built-in providers:
 * <ul>
 *   <li>In-memory (priority 0) - Default, single-instance only</li>
 *   <li>Redis (priority 10) - Distributed, recommended for production</li>
 * </ul>
 *
 * <p>To create a custom provider:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Register in {@code META-INF/services/aussie.spi.RateLimiterProvider}</li>
 *   <li>Return appropriate priority</li>
 * </ol>
 *
 * @see aussie.core.port.out.RateLimiter
 */
public interface RateLimiterProvider {

    /**
     * Return the priority of this provider.
     *
     * <p>Higher values indicate higher priority. When multiple providers are
     * available, the one with the highest priority is selected.
     *
     * <p>Standard priorities:
     * <ul>
     *   <li>0 - In-memory (fallback)</li>
     *   <li>10 - Redis (production default)</li>
     *   <li>100+ - Custom implementations</li>
     * </ul>
     *
     * @return the provider priority
     */
    int priority();

    /**
     * Return the name of this provider for logging and configuration.
     *
     * @return the provider name (e.g., "memory", "redis")
     */
    String name();

    /**
     * Check if this provider is available in the current environment.
     *
     * <p>Providers should check for required dependencies or configuration.
     * For example, the Redis provider checks for Redis connectivity.
     *
     * @return true if the provider can be used
     */
    boolean isAvailable();

    /**
     * Create a rate limiter instance.
     *
     * <p>Called once during application startup. The returned instance should
     * be thread-safe and suitable for CDI injection.
     *
     * @return the rate limiter instance
     */
    RateLimiter createRateLimiter();
}
