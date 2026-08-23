package aussie.core.service.gateway;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
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
    private final ProxyRequestPreparer requestPreparer;
    private final RouteAuthenticationService routeAuthService;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            RouteAuthenticationService routeAuthService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.routeAuthService = routeAuthService;
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
                    .map(authResult -> handleAuthResult(authResult, request, routeMatch));
        });
    }

    private ProxyPlan handleAuthResult(RouteAuthResult authResult, GatewayRequest request, RouteMatch routeMatch) {
        return switch (authResult) {
            case RouteAuthResult.Authenticated auth -> new ProxyPlan.Ready(
                    request,
                    requestPreparer.prepare(
                            request,
                            routeMatch,
                            auth.token().hasToken() ? Optional.of(auth.token()) : Optional.empty()),
                    routeMatch.service());
            case RouteAuthResult.NotRequired notRequired -> new ProxyPlan.Ready(
                    request, requestPreparer.prepare(request, routeMatch, Optional.empty()), routeMatch.service());
            case RouteAuthResult.Unauthorized unauthorized -> new ProxyPlan.Rejected(
                    new GatewayResult.Unauthorized(unauthorized.reason()),
                    routeMatch.service().serviceId());
            case RouteAuthResult.Forbidden forbidden -> new ProxyPlan.Rejected(
                    new GatewayResult.Forbidden(forbidden.reason()),
                    routeMatch.service().serviceId());
            case RouteAuthResult.BadRequest badRequest -> new ProxyPlan.Rejected(
                    new GatewayResult.BadRequest(badRequest.reason()),
                    routeMatch.service().serviceId());
        };
    }
}
