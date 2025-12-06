package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.AuthKeyCache;
import aussie.core.port.out.StorageHealthIndicator;

/**
 * Service Provider Interface for API key cache implementations.
 *
 * <p>Platform teams implement this interface to provide custom caching layers
 * for API key validation. Implementations are discovered via java.util.ServiceLoader.
 *
 * <p>Caching is optional but recommended for high-throughput environments to reduce
 * database lookups during authentication.
 *
 * <h2>Configuration</h2>
 * <pre>
 * aussie.auth.cache.enabled=true
 * aussie.auth.cache.provider=redis
 * aussie.auth.cache.ttl=PT5M
 * </pre>
 */
public interface AuthKeyCacheProvider {

    /**
     * Unique name identifying this provider.
     *
     * <p>Used in configuration: aussie.auth.cache.provider={name}
     *
     * @return The provider name
     */
    String name();

    /**
     * Human-readable description of this provider.
     *
     * @return Description for logging and diagnostics
     */
    default String description() {
        return name() + " auth key cache provider";
    }

    /**
     * Priority for auto-selection when no explicit provider is configured.
     *
     * <p>Higher values = higher priority.
     *
     * @return The provider priority
     */
    default int priority() {
        return 0;
    }

    /**
     * Check if this provider is available (dependencies present, etc.)
     *
     * <p>Called before createCache to skip unavailable providers.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create the cache implementation.
     *
     * <p>Called once at startup. The returned instance should be thread-safe.
     *
     * @param config Access to configuration properties
     * @return Cache implementation
     * @throws StorageProviderException if initialization fails
     */
    AuthKeyCache createCache(StorageAdapterConfig config);

    /**
     * Optionally provide a health indicator for this cache backend.
     *
     * @param config Access to configuration properties
     * @return Health indicator, or empty if not supported
     */
    default Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.empty();
    }
}
