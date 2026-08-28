package aussie.system.filter;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.common.context.PlatformPaths;
import aussie.common.context.RouteContextAttributes;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.service.lifecycle.StartupState;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Resolve the registered route for the inbound request once and cache the
 * result on {@link RoutingContext}. Downstream consumers (auth mechanisms,
 * rate limit/access-control filters) read the cached lookup instead of
 * re-querying {@link ServiceRegistry}.
 *
 * <p>Priority 105 places this filter before the CORS filter (100), so both
 * CORS and downstream security filters consume the same route snapshot.
 */
@ApplicationScoped
public class RouteResolutionFilter {

    private final ServiceRegistry serviceRegistry;
    private final StartupState startupState;
    private final ProxyErrorWriter errorWriter;

    @Inject
    public RouteResolutionFilter(
            ServiceRegistry serviceRegistry, StartupState startupState, ProxyErrorWriter errorWriter) {
        this.serviceRegistry = serviceRegistry;
        this.startupState = startupState;
        this.errorWriter = errorWriter;
    }

    @RouteFilter(105)
    void resolveRoute(RoutingContext rc) {
        final var path = rc.request().path();
        if (PlatformPaths.owns(path)) {
            rc.put(RouteContextAttributes.LOOKUP, Optional.empty());
            rc.next();
            return;
        }
        if (!startupState.isReady()) {
            errorWriter.write(rc, ProblemDetail.serviceUnavailable("Gateway is not ready"));
            return;
        }

        final var method = requestMethod(rc);
        serviceRegistry
                .findRouteAsync(path, method)
                .subscribe()
                .with(
                        lookup -> {
                            rc.put(RouteContextAttributes.LOOKUP, lookup);
                            if (lookup.isPresent() && isPublic(lookup.get())) {
                                rc.put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
                            }
                            rc.next();
                        },
                        rc::fail);
    }

    private static String requestMethod(RoutingContext rc) {
        if (!"OPTIONS".equalsIgnoreCase(rc.request().method().name())) {
            return rc.request().method().name();
        }
        final var requestedMethod = rc.request().getHeader("Access-Control-Request-Method");
        return requestedMethod == null || requestedMethod.isBlank() ? "OPTIONS" : requestedMethod;
    }

    private static boolean isPublic(RouteLookupResult route) {
        return route.visibility() == EndpointVisibility.PUBLIC;
    }
}
