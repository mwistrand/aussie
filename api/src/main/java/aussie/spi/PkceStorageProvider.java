package aussie.spi;

import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;

import aussie.core.port.out.PkceChallengeRepository;

/**
 * SPI for PKCE challenge storage implementations.
 *
 * <p>Platform teams can implement this interface to provide custom PKCE
 * storage backends (e.g., Memcached via AWS ElastiCache, DynamoDB, etc.).
 *
 * <p>Built-in providers:
 * <ul>
 *   <li>redis (priority: 100) - Redis-based storage</li>
 *   <li>memory (priority: 0) - In-memory storage (development only)</li>
 * </ul>
 *
 * <p>Provider selection uses the explicitly configured provider when present;
 * automatic priority selection is used only when no provider is configured.
 * An unavailable explicitly configured provider is not replaced with memory.
 *
 * <h2>Custom Implementation Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class ElastiCachePkceStorageProvider implements PkceStorageProvider {
 *
 *     @Override
 *     public String name() {
 *         return "elasticache";
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 150; // Higher than Redis
 *     }
 *
 *     @Override
 *     public boolean isAvailable() {
 *         // Check if ElastiCache connection is available
 *         return elastiCacheClient.isConnected();
 *     }
 *
 *     @Override
 *     public PkceChallengeRepository createRepository() {
 *         return new ElastiCachePkceChallengeRepository(elastiCacheClient);
 *     }
 * }
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <pre>
 * # Select provider by name
 * aussie.auth.pkce.storage.provider=elasticache
 * </pre>
 *
 * @see PkceChallengeRepository
 */
public interface PkceStorageProvider {

    /**
     * Return the provider name for configuration selection.
     *
     * <p>This name is used in the configuration property
     * {@code aussie.auth.pkce.storage.provider} to select this provider.
     *
     * @return Provider name (e.g., "redis", "memory", "elasticache")
     */
    String name();

    /**
     * Return the provider priority for automatic selection.
     *
     * <p>Higher priority providers are preferred during automatic selection.
     * Built-in priorities:
     * <ul>
     *   <li>redis: 100</li>
     *   <li>memory: 0 (development/test only)</li>
     * </ul>
     *
     * <p>Custom implementations should use priorities above 100 to be
     * preferred over Redis, or between 0 and 100 for lower-priority selection.
     *
     * @return Priority value (higher = more preferred)
     */
    int priority();

    /**
     * Check whether this provider currently reports healthy.
     *
     * <p>For Redis, this checks if the connection is active.
     * For memory, this always returns true.
     *
     * <p>This signal is used for health reporting and automatic selection.
     * Explicitly configured providers remain selected while unavailable so
     * repository operations fail closed. This method may be called multiple
     * times and should return quickly. Consider caching the availability state.
     *
     * @return true if the provider is healthy and eligible for automatic selection
     */
    boolean isAvailable();

    /**
     * Create the PKCE challenge repository implementation.
     *
     * <p>The returned repository must implement:
     * <ul>
     *   <li>Automatic TTL-based expiration of challenges</li>
     *   <li>Atomic consume (retrieve-and-delete) operations</li>
     *   <li>Non-blocking reactive operations</li>
     * </ul>
     *
     * @return PKCE challenge repository instance
     */
    PkceChallengeRepository createRepository();

    /**
     * Create a health indicator for this storage backend.
     *
     * <p>The health indicator is used for the /q/health endpoint
     * to report the status of PKCE storage.
     *
     * @return Health check response, or empty if not supported
     */
    default Optional<HealthCheckResponse> healthCheck() {
        return Optional.empty();
    }
}
