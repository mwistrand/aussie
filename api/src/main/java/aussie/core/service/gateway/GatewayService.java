package aussie.core.service.gateway;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
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

        // Use async route lookup to ensure cache freshness in multi-instance deployments
        return serviceRegistry.findRouteAsync(request.path(), request.method()).flatMap(routeResult -> {
            if (routeResult.isEmpty()) {
                var result = new GatewayResult.RouteNotFound(request.path());
                return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
            }

            // Gateway requires a RouteMatch (with endpoint) to forward requests
            if (!(routeResult.get() instanceof RouteMatch routeMatch)) {
                var result = new GatewayResult.RouteNotFound(request.path());
                return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
            }

            // Check route authentication requirements
            return routeAuthService
                    .authenticate(request, routeMatch)
                    .map(authResult -> proxyPlanBuilder.build(request, routeMatch, authResult));
        });
    }
}
