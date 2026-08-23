package aussie.core.model.service;

/**
 * Events published when service configurations change.
 *
 * <p>Used for cross-instance cache invalidation via pub/sub.
 * Events carry the service ID and durable generation; receiving instances rebuild
 * the full snapshot from persistent storage.
 */
public sealed interface ServiceConfigEvent {

    /**
     * The service ID affected by this event.
     */
    String serviceId();

    /** Durable configuration generation observed by the publisher. */
    long generation();

    /**
     * A service was registered or updated.
     *
     * @param serviceId the registered/updated service ID
     */
    record ServiceChanged(String serviceId, long generation) implements ServiceConfigEvent {
        public ServiceChanged(String serviceId) {
            this(serviceId, 0L);
        }
    }

    /**
     * A service was unregistered.
     *
     * @param serviceId the removed service ID
     */
    record ServiceRemoved(String serviceId, long generation) implements ServiceConfigEvent {
        public ServiceRemoved(String serviceId) {
            this(serviceId, 0L);
        }
    }
}
