package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigRepository;

/**
 * Service Provider Interface for translation config storage implementations.
 *
 * <p>Platform teams implement this interface to provide custom storage backends
 * for translation configurations (e.g., DynamoDB, PostgreSQL, MongoDB).
 *
 * <p>Implementations are discovered via java.util.ServiceLoader at startup.
 *
 * <h2>How to Create a Custom Provider</h2>
 * <ol>
 *   <li>Add aussie-spi as a dependency to your project</li>
 *   <li>Implement this interface</li>
 *   <li>Create META-INF/services/aussie.spi.TranslationConfigStorageProvider</li>
 *   <li>Add your fully qualified class name to the file</li>
 *   <li>Package as JAR and add to Aussie's classpath</li>
 *   <li>Configure: aussie.translation-config.storage.provider=your-provider-name</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class DynamoDBTranslationConfigProvider implements TranslationConfigStorageProvider {
 *     @Override
 *     public String name() { return "dynamodb"; }
 *
 *     @Override
 *     public TranslationConfigRepository createRepository(StorageAdapterConfig config) {
 *         String tableName = config.getOrDefault(
 *             "aussie.translation-config.dynamodb.table", "translation_configs");
 *         return new DynamoDBTranslationConfigRepository(tableName);
 *     }
 * }
 * }</pre>
 */
public interface TranslationConfigStorageProvider {

    /**
     * Unique name identifying this provider.
     *
     * <p>Used in configuration: aussie.translation-config.storage.provider={name}
     *
     * @return The provider name (e.g., "cassandra", "dynamodb", "postgresql")
     */
    String name();

    /**
     * Human-readable description of this provider.
     *
     * @return Description for logging and diagnostics
     */
    default String description() {
        return name() + " translation config storage provider";
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
     *   <li>memory: 0 (fallback default)</li>
     *   <li>cassandra: 10</li>
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
     * Use this to check for required dependencies, environment variables,
     * or configuration.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create the repository implementation.
     *
     * <p>Called once at startup. The returned instance should be thread-safe
     * and support concurrent access from multiple requests.
     *
     * @param config Access to configuration properties
     * @return Repository implementation
     * @throws StorageProviderException if initialization fails
     */
    TranslationConfigRepository createRepository(StorageAdapterConfig config);

    /**
     * Optionally provide a health indicator for this storage backend.
     *
     * <p>Health indicators are used by the /q/health endpoint to report
     * storage backend health.
     *
     * @param config Access to configuration properties
     * @return Health indicator, or empty if not supported
     */
    default Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.empty();
    }
}
