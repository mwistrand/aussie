package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.ApiKeyRepository;
import aussie.core.port.out.StorageHealthIndicator;

/**
 * Service Provider Interface for API key storage implementations.
 *
 * <p>Platform teams implement this interface to provide custom storage backends
 * for API keys. Implementations are discovered via java.util.ServiceLoader at startup.
 *
 * <p>This SPI is separate from {@link StorageRepositoryProvider} to allow:
 * <ul>
 *   <li>Independent configuration namespaces (aussie.auth.storage.*)</li>
 *   <li>Different encryption requirements for API keys</li>
 *   <li>Specialized caching strategies for authentication</li>
 * </ul>
 *
 * <h2>How to Create a Custom Provider</h2>
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Create META-INF/services/aussie.spi.AuthKeyStorageProvider</li>
 *   <li>Add your fully qualified class name to the file</li>
 *   <li>Configure: aussie.auth.storage.provider=your-provider-name</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public class VaultAuthKeyStorageProvider implements AuthKeyStorageProvider {
 *     @Override
 *     public String name() { return "vault"; }
 *
 *     @Override
 *     public ApiKeyRepository createRepository(StorageAdapterConfig config) {
 *         String vaultUrl = config.getRequired("aussie.auth.storage.vault.url");
 *         return new VaultApiKeyRepository(vaultUrl);
 *     }
 * }
 * }</pre>
 */
public interface AuthKeyStorageProvider {

    /**
     * Unique name identifying this provider.
     *
     * <p>Used in configuration: aussie.auth.storage.provider={name}
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
        return name() + " auth key storage provider";
    }

    /**
     * Priority for auto-selection when no explicit provider is configured.
     *
     * <p>Higher values = higher priority.
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
    ApiKeyRepository createRepository(StorageAdapterConfig config);

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
