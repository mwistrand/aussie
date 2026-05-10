package aussie.system.filter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Resolve the registered route for the inbound request once and cache the
 * result on {@link RoutingContext}. Downstream consumers (auth mechanisms,
 * rate limit/access-control filters) read the cached lookup instead of
 * re-querying {@link ServiceRegistry}.
 *
 * <p>Priority 95 places this filter between the existing CORS filter (100)
 * and the security-headers filter (90). CORS preflights short-circuit at
 * priority 100 without calling {@code next()}, so route resolution is not
 * paid for OPTIONS preflights.
 */
@ApplicationScoped
public class RouteResolutionFilter {

    /**
     * {@link RoutingContext} key for the {@code Optional<RouteLookupResult>}
     * produced by {@link ServiceRegistry#findRoute(String, String)}.
     */
    public static final String LOOKUP_KEY = "aussie.route.lookup";

    /**
     * {@link RoutingContext} key for the {@code Boolean} flag that is set to
     * {@code Boolean.TRUE} when the resolved route's effective visibility is
     * {@link EndpointVisibility#PUBLIC}. Absent otherwise.
     */
    public static final String PUBLIC_KEY = "aussie.route.public";

    private final ServiceRegistry serviceRegistry;

    @Inject
    public RouteResolutionFilter(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @RouteFilter(95)
    void resolveRoute(RoutingContext rc) {
        final var path = rc.request().path();
        final var method = rc.request().method().name();

        final var lookup = serviceRegistry.findRoute(path, method);
        rc.put(LOOKUP_KEY, lookup);

        if (lookup.isPresent() && isPublic(lookup.get())) {
            rc.put(PUBLIC_KEY, Boolean.TRUE);
        }

        rc.next();
    }

    private static boolean isPublic(RouteLookupResult route) {
        return route.visibility() == EndpointVisibility.PUBLIC;
    }
}
