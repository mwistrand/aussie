package aussie.core.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for service configuration pub/sub.
 *
 * <p>Configuration prefix: {@code aussie.service.pubsub}
 *
 * <p>When enabled, service configuration changes (register, update, delete)
 * are published to all Aussie instances via Redis pub/sub, allowing them
 * to immediately refresh their local caches instead of waiting for
 * TTL-based expiration.
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_SERVICE_PUBSUB_ENABLED} - enable/disable pub/sub</li>
 *   <li>{@code AUSSIE_SERVICE_PUBSUB_TOPIC} - topic name for events</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.service.pubsub")
public interface ServiceConfigPubSubConfig {

    /**
     * Enable pub/sub for service configuration events.
     *
     * <p>When enabled, configuration changes are published to other
     * Aussie instances for immediate cache invalidation.
     *
     * @return true if pub/sub is enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Topic name for service configuration events.
     *
     * <p>Mapped to a transport-specific destination (e.g., a Redis pub/sub
     * channel) by the active {@link aussie.core.port.out.ServiceConfigEventPublisher} implementation.
     *
     * @return topic name (default: aussie:service:config:events)
     */
    @WithDefault("aussie:service:config:events")
    String topic();
}
