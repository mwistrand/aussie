package aussie.spi;

import java.util.Optional;

import aussie.core.port.out.GroupRepository;
import aussie.core.port.out.StorageHealthIndicator;

/**
 * Service Provider Interface for group storage backends.
 *
 * <p>Implementations provide persistent storage for RBAC groups, which map
 * to sets of permissions. Groups are used to expand token group claims
 * into effective permissions at validation time.
 *
 * <p>Providers are discovered via ServiceLoader. Configure the preferred
 * provider with aussie.auth.groups.storage.provider, or let the loader
 * select the highest priority available provider.
 *
 * <p>To implement a custom provider:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Create a META-INF/services/aussie.spi.GroupStorageProvider file</li>
 *   <li>Add the fully qualified class name to the file</li>
 * </ol>
 */
public interface GroupStorageProvider {

    /**
     * Get the provider name.
     *
     * @return short name for configuration (e.g., "memory", "cassandra")
     */
    String name();

    /**
     * Get the provider description.
     *
     * @return human-readable description
     */
    String description();

    /**
     * Get the provider priority.
     *
     * <p>Higher priority providers are preferred when auto-selecting.
     * Memory provider should use 0, persistent providers should use higher values.
     *
     * @return priority value (higher = more preferred)
     */
    int priority();

    /**
     * Check if this provider is available.
     *
     * <p>Used to filter out providers whose dependencies are not available
     * (e.g., Cassandra driver not on classpath).
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create the group repository.
     *
     * @param config configuration adapter
     * @return the repository instance
     */
    GroupRepository createRepository(StorageAdapterConfig config);

    /**
     * Create a health indicator for this storage backend.
     *
     * @param config configuration adapter
     * @return optional health indicator
     */
    default Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.empty();
    }
}
