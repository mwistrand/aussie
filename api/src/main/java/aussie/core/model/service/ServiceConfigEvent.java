package aussie.core.model.service;

/**
 * Events published when service configurations change.
 *
 * <p>Used for cross-instance cache invalidation via pub/sub.
 * Events carry only the service ID; receiving instances re-fetch
 * the full registration from persistent storage.
 */
public sealed interface ServiceConfigEvent {

    /**
     * The service ID affected by this event.
     */
    String serviceId();

    /**
     * A service was registered or updated.
     *
     * @param serviceId the registered/updated service ID
     */
    record ServiceChanged(String serviceId) implements ServiceConfigEvent {}

    /**
     * A service was unregistered.
     *
     * @param serviceId the removed service ID
     */
    record ServiceRemoved(String serviceId) implements ServiceConfigEvent {}
}
