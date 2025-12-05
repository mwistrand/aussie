package aussie.core.port.out;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ServiceRegistration;

/**
 * Optional port interface for caching service registrations.
 *
 * <p>Caching is NOT required for Aussie to function. This interface allows
 * platform teams to add a cache layer (Redis, Hazelcast, Caffeine, etc.)
 * to reduce load on the primary storage backend.
 *
 * <p>If no cache provider is configured, Aussie reads directly from the
 * repository on every request.
 */
public interface ConfigurationCache {

    /**
     * Get a cached service registration.
     *
     * @param serviceId The service identifier
     * @return Uni with Optional containing cached value if present
     */
    Uni<Optional<ServiceRegistration>> get(String serviceId);

    /**
     * Cache a service registration with default TTL.
     *
     * @param registration The registration to cache
     * @return Uni completing when cached
     */
    Uni<Void> put(ServiceRegistration registration);

    /**
     * Cache a service registration with custom TTL.
     *
     * @param registration The registration to cache
     * @param ttl Time-to-live duration
     * @return Uni completing when cached
     */
    Uni<Void> put(ServiceRegistration registration, Duration ttl);

    /**
     * Invalidate a cached entry.
     *
     * @param serviceId The service identifier to invalidate
     * @return Uni completing when invalidated
     */
    Uni<Void> invalidate(String serviceId);

    /**
     * Invalidate all cached entries.
     *
     * @return Uni completing when all invalidated
     */
    Uni<Void> invalidateAll();
}
