package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.SamplingConfigRepository;
import aussie.core.port.out.StorageHealthIndicator;

/**
 * Service Provider Interface for sampling configuration storage.
 *
 * <p>Platform teams implement this interface to provide custom storage backends
 * for sampling configurations. Implementations are discovered via
 * {@link java.util.ServiceLoader} at startup.
 *
 * <h2>How to Create a Custom Provider</h2>
 * <ol>
 *   <li>Add aussie-spi as a dependency to your project</li>
 *   <li>Implement this interface</li>
 *   <li>Create META-INF/services/aussie.spi.SamplingConfigProvider</li>
 *   <li>Add your fully qualified class name to the file</li>
 *   <li>Package as JAR and add to Aussie's classpath</li>
 *   <li>Configure: aussie.telemetry.sampling.storage.provider=your-provider-name</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class DynamoDBSamplingConfigProvider implements SamplingConfigProvider {
 *     @Override
 *     public String name() { return "dynamodb"; }
 *
 *     @Override
 *     public SamplingConfigRepository createRepository(StorageAdapterConfig config) {
 *         String region = config.getRequired("aussie.sampling.dynamodb.region");
 *         return new DynamoDBSamplingConfigRepository(region);
 *     }
 * }
 * }</pre>
 */
public interface SamplingConfigProvider {

    /**
     * Unique name identifying this provider.
     *
     * <p>Used in configuration: aussie.telemetry.sampling.storage.provider={name}
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
        return name() + " sampling config provider";
    }

    /**
     * Priority for auto-selection when no explicit provider is configured.
     *
     * <p>Higher values = higher priority.
     *
     * <p>Built-in providers use:
     * <ul>
     *   <li>memory: 0 (fallback default)</li>
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
     * <p>Called before createRepository to skip unavailable providers.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create the repository implementation.
     *
     * <p>Called once at startup. The returned instance should be thread-safe.
     *
     * @param config Access to configuration properties
     * @return Repository implementation
     * @throws StorageProviderException if initialization fails
     */
    SamplingConfigRepository createRepository(StorageAdapterConfig config);

    /**
     * Optionally provide a health indicator for this storage backend.
     *
     * @param config Access to configuration properties
     * @return Health indicator, or empty if not supported
     */
    default Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.empty();
    }
}
