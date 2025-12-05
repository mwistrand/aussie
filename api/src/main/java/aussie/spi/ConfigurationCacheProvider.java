package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.StorageHealthIndicator;

/**
 * Service Provider Interface for cache implementations.
 *
 * <p>This is OPTIONAL. If no cache provider is configured or available,
 * Aussie operates without caching (reads go directly to the repository).
 *
 * <p>Platform teams implement this interface to add a cache layer.
 */
public interface ConfigurationCacheProvider {

    /**
     * Unique name identifying this cache provider.
     *
     * <p>Used in configuration: aussie.storage.cache.provider={name}
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
        return name() + " cache provider";
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
    ConfigurationCache createCache(StorageAdapterConfig config);

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
