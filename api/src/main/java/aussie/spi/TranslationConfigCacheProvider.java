package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigCache;

/**
 * Service Provider Interface for translation config cache implementations.
 *
 * <p>This is OPTIONAL. If no cache provider is configured or available,
 * Aussie operates with only the local in-memory cache (L1).
 *
 * <p>Platform teams implement this interface to add a distributed cache layer
 * (e.g., ElastiCache, Memcached, Hazelcast) between the local cache and
 * primary storage.
 *
 * <h2>Cache Architecture</h2>
 * <pre>
 * L1 (Memory) → L2 (Distributed Cache - this SPI) → L3 (Primary Storage)
 * </pre>
 *
 * <h2>How to Create a Custom Provider</h2>
 * <ol>
 *   <li>Add aussie-spi as a dependency to your project</li>
 *   <li>Implement this interface</li>
 *   <li>Create META-INF/services/aussie.spi.TranslationConfigCacheProvider</li>
 *   <li>Add your fully qualified class name to the file</li>
 *   <li>Package as JAR and add to Aussie's classpath</li>
 *   <li>Configure: aussie.translation-config.cache.provider=your-provider-name</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class ElastiCacheTranslationConfigProvider implements TranslationConfigCacheProvider {
 *     @Override
 *     public String name() { return "elasticache"; }
 *
 *     @Override
 *     public TranslationConfigCache createCache(StorageAdapterConfig config) {
 *         String endpoint = config.getRequired("aussie.translation-config.elasticache.endpoint");
 *         Duration ttl = config.getDuration("aussie.translation-config.cache.ttl")
 *             .orElse(Duration.ofMinutes(15));
 *         return new ElastiCacheTranslationConfigCache(endpoint, ttl);
 *     }
 * }
 * }</pre>
 */
public interface TranslationConfigCacheProvider {

    /**
     * Unique name identifying this cache provider.
     *
     * <p>Used in configuration: aussie.translation-config.cache.provider={name}
     *
     * @return The provider name (e.g., "redis", "elasticache", "memcached")
     */
    String name();

    /**
     * Human-readable description of this provider.
     *
     * @return Description for logging and diagnostics
     */
    default String description() {
        return name() + " translation config cache provider";
    }

    /**
     * Priority for auto-selection when no explicit provider is configured.
     *
     * <p>Higher values = higher priority. When multiple providers are available
     * and no explicit provider is configured, the highest priority available
     * provider is selected.
     *
     * <p>Built-in providers use:
     * <ul>
     *   <li>redis: 10</li>
     * </ul>
     *
     * <p>Custom providers should use priority &gt; 10 to override defaults.
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
     * Use this to check for required dependencies, connections,
     * or configuration.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create the cache implementation.
     *
     * <p>Called once at startup. The returned instance should be thread-safe
     * and support concurrent access from multiple requests.
     *
     * @param config Access to configuration properties
     * @return Cache implementation
     * @throws StorageProviderException if initialization fails
     */
    TranslationConfigCache createCache(StorageAdapterConfig config);

    /**
     * Optionally provide a health indicator for this cache backend.
     *
     * <p>Health indicators are used by the /q/health endpoint to report
     * cache backend health.
     *
     * @param config Access to configuration properties
     * @return Health indicator, or empty if not supported
     */
    default Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.empty();
    }
}
