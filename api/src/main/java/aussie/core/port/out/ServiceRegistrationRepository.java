package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.service.ServiceRegistration;

/**
 * Port interface for persistent storage of service registrations.
 *
 * <p>This is the primary storage abstraction. All implementations must provide
 * durable persistence—data must survive application restarts.
 *
 * <p>Implementations may use any backing store: Cassandra, DynamoDB, PostgreSQL,
 * MongoDB, file system, etc.
 */
public interface ServiceRegistrationRepository {

    /**
     * Save or update a service registration.
     *
     * @param registration The service registration to persist
     * @return Uni completing when save is durable
     */
    Uni<Void> save(ServiceRegistration registration);

    /**
     * Find a service registration by its unique identifier.
     *
     * @param serviceId The service identifier
     * @return Uni with Optional containing the registration if found
     */
    Uni<Optional<ServiceRegistration>> findById(String serviceId);

    /**
     * Delete a service registration.
     *
     * @param serviceId The service identifier to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String serviceId);

    /**
     * Retrieve all service registrations.
     *
     * @return Uni with list of all registrations
     */
    Uni<List<ServiceRegistration>> findAll();

    /**
     * Check if a service exists.
     *
     * @param serviceId The service identifier
     * @return Uni with true if exists
     */
    Uni<Boolean> exists(String serviceId);

    /**
     * Count total registrations.
     *
     * @return Uni with count
     */
    Uni<Long> count();
}
