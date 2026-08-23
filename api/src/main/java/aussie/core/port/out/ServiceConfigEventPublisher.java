package aussie.core.port.out;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import aussie.core.model.service.ServiceConfigEvent;

/**
 * SPI for publishing service configuration events to other Aussie instances.
 *
 * <p>In multi-instance deployments, configuration changes must be propagated
 * so that all instances can immediately refresh their local caches instead
 * of waiting for TTL expiration.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Events should be delivered to all subscribed instances</li>
 *   <li>Delivery is best-effort (instances fall back to TTL-based refresh)</li>
 *   <li>All operations MUST be non-blocking (return Uni/Multi)</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Platform teams can provide custom implementations:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class KafkaServiceConfigEventPublisher implements ServiceConfigEventPublisher {
 *     // Custom implementation using Kafka
 * }
 * }</pre>
 *
 * @see aussie.adapter.out.storage.redis.RedisServiceConfigEventPublisher
 */
public interface ServiceConfigEventPublisher {

    /**
     * Publish that a service was registered or updated.
     *
     * @param serviceId the service that changed
     * @return Uni completing when the event is published
     */
    Uni<Void> publishServiceChanged(String serviceId);

    default Uni<Void> publishServiceChanged(String serviceId, long generation) {
        return publishServiceChanged(serviceId);
    }

    /**
     * Publish that a service was removed.
     *
     * @param serviceId the service that was removed
     * @return Uni completing when the event is published
     */
    Uni<Void> publishServiceRemoved(String serviceId);

    default Uni<Void> publishServiceRemoved(String serviceId, long generation) {
        return publishServiceRemoved(serviceId);
    }

    /**
     * Subscribe to service configuration events from other instances.
     *
     * <p>The returned Multi should emit events as they are received.
     * Implementations should handle reconnection transparently.
     *
     * @return Multi streaming service config events
     */
    Multi<ServiceConfigEvent> subscribe();
}
