package aussie.core.model.routing;

import aussie.core.model.service.ServiceRegistration;

/**
 * Pre-baked per-service lookup record holding the registration plus its compiled
 * route index. Exists so the request hot path can resolve a service and dispatch
 * an endpoint match in two pointer-chasing operations with no per-request cache
 * lookup or {@link java.util.Optional} allocation.
 *
 * <p>Built once at registration / refresh time and stored in the active
 * {@link GatewaySnapshot}.
 */
public record ServiceRoutes(ServiceRegistration service, RouteIndex index) {

    public ServiceRoutes {
        if (service == null) {
            throw new IllegalArgumentException("service cannot be null");
        }
        if (index == null) {
            throw new IllegalArgumentException("index cannot be null");
        }
    }

    public static ServiceRoutes of(ServiceRegistration service) {
        return new ServiceRoutes(service, RouteIndex.build(service.endpoints()));
    }

    /**
     * Find a matching endpoint or return {@code null}. Uses the nullable hot-path
     * variant of {@link RouteIndex#match} to skip the per-request Optional allocation.
     *
     * @param normalizedPath path beginning with '/'
     * @param upperMethod    HTTP method already upper-cased by the caller
     */
    public RouteMatch matchEndpoint(String normalizedPath, String upperMethod) {
        return index.match(service, normalizedPath, upperMethod);
    }
}
