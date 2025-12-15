package aussie.spi;

import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;

import aussie.core.port.out.SessionRepository;

/**
 * SPI for custom session storage implementations.
 *
 * <p>Platform teams can implement this interface to provide custom session
 * storage backends (e.g., database, distributed cache, external service).
 *
 * <p>Built-in providers:
 * <ul>
 *   <li>redis (priority: 100) - Redis-based storage</li>
 *   <li>memory (priority: 0) - In-memory storage (development only)</li>
 * </ul>
 *
 * <p>Provider selection order:
 * <ol>
 *   <li>Configured provider (aussie.session.storage.provider)</li>
 *   <li>Highest priority available provider</li>
 *   <li>Memory fallback (always available)</li>
 * </ol>
 */
public interface SessionStorageProvider {

    /**
     * Return the provider name for configuration selection.
     *
     * <p>This name is used in the configuration property
     * {@code aussie.session.storage.provider} to select this provider.
     *
     * @return Provider name (e.g., "redis", "memory", "dynamodb")
     */
    String name();

    /**
     * Return the provider priority for automatic selection.
     *
     * <p>Higher priority providers are preferred when multiple providers
     * are available. Built-in priorities:
     * <ul>
     *   <li>redis: 100</li>
     *   <li>memory: 0 (fallback only)</li>
     * </ul>
     *
     * @return Priority value (higher = more preferred)
     */
    int priority();

    /**
     * Check if this provider is available and ready to use.
     *
     * <p>For Redis, this checks if the connection is active.
     * For memory, this always returns true.
     *
     * @return true if the provider can be used
     */
    boolean isAvailable();

    /**
     * Create the session repository implementation.
     *
     * @return Session repository instance
     */
    SessionRepository createRepository();

    /**
     * Create a health indicator for this storage backend.
     *
     * <p>The health indicator is used for the /q/health endpoint
     * to report the status of session storage.
     *
     * @return Health check response builder, or empty if not supported
     */
    Optional<HealthCheckResponse> healthCheck();
}
