package aussie.core.service.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.port.in.GatewayUseCase;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Handle gateway proxy requests using configured route matching.
 *
 * <p>Gateway mode provides full route matching capabilities where requests
 * are matched against registered endpoint patterns. This enables:
 * <ul>
 *   <li>Path-based routing with wildcards and path variables</li>
 *   <li>Per-endpoint visibility and authentication settings</li>
 *   <li>Request transformation and path rewriting</li>
 * </ul>
 *
 * <p>The service coordinates authentication, authorization, and request preparation.
 * The inbound adapter executes the resulting proxy plan.
 *
 * <p>All operations are fully reactive and never block.
 */
@ApplicationScoped
public class GatewayService implements GatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final RouteAuthenticationService routeAuthService;
    private final ProxyPlanBuilder proxyPlanBuilder;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry,
            RouteAuthenticationService routeAuthService,
            ProxyPlanBuilder proxyPlanBuilder) {
        this.serviceRegistry = serviceRegistry;
        this.routeAuthService = routeAuthService;
        this.proxyPlanBuilder = proxyPlanBuilder;
    }

    @Override
    public Uni<ProxyPlan> prepare(GatewayRequest request) {
        if (request.hasRouteSnapshot()) {
            return request.resolvedRoute()
                    .map(route -> prepareRoute(request, route))
                    .orElseGet(() -> routeNotFound(request));
        }

        // Use async route lookup to ensure cache freshness in multi-instance deployments
        return serviceRegistry.findRouteAsync(request.path(), request.method()).flatMap(routeResult -> routeResult
                .map(route -> prepareRoute(request, route))
                .orElseGet(() -> routeNotFound(request)));
    }

    private Uni<ProxyPlan> routeNotFound(GatewayRequest request) {
        return Uni.createFrom().item(new ProxyPlan.Rejected(new GatewayResult.RouteNotFound(request.path()), null));
    }

    private Uni<ProxyPlan> prepareRoute(GatewayRequest request, RouteLookupResult routeResult) {
        // Gateway requires a RouteMatch (with endpoint) to forward requests
        if (!(routeResult instanceof RouteMatch routeMatch)) {
            var result = new GatewayResult.RouteNotFound(request.path());
            return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
        }

        // Check route authentication requirements
        return routeAuthService
                .authenticate(request, routeMatch)
                .map(authResult -> proxyPlanBuilder.build(request, routeMatch, authResult));
    }
}
