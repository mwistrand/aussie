package aussie.core.port.out;

import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.RevocationEvent;

/**
 * SPI for publishing revocation events to other Aussie instances.
 *
 * <p>In multi-instance deployments, revocation events must be propagated
 * to keep bloom filters synchronized across all instances. This interface
 * abstracts the pub/sub mechanism.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Events should be delivered to all subscribed instances</li>
 *   <li>Delivery should be best-effort (revocation check falls back to remote store)</li>
 *   <li>All operations MUST be non-blocking (return Uni/Multi)</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Platform teams can provide custom implementations:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class KafkaRevocationEventPublisher implements RevocationEventPublisher {
 *     // Custom implementation using Kafka
 * }
 * }</pre>
 *
 * @see aussie.adapter.out.storage.redis.RedisRevocationEventPublisher
 */
public interface RevocationEventPublisher {

    /**
     * Publish a JTI revocation event to other instances.
     *
     * @param jti       the revoked JWT ID
     * @param expiresAt when the revocation expires
     * @return Uni completing when the event is published
     */
    Uni<Void> publishJtiRevoked(String jti, Instant expiresAt);

    /**
     * Publish a user revocation event to other instances.
     *
     * @param userId       the user whose tokens were revoked
     * @param issuedBefore tokens issued before this time are revoked
     * @param expiresAt    when the revocation expires
     * @return Uni completing when the event is published
     */
    Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt);

    /**
     * Subscribe to revocation events from other instances.
     *
     * <p>The returned Multi should emit events as they are received.
     * Implementations should handle reconnection transparently.
     *
     * @return Multi streaming revocation events
     */
    Multi<RevocationEvent> subscribe();
}
