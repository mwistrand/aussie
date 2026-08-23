package aussie.core.service.gateway;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.in.PassThroughUseCase;
import aussie.core.service.routing.EndpointMatcher;
import aussie.core.service.routing.ServiceRegistry;
import aussie.core.service.routing.VisibilityResolver;

/**
 * Handle pass-through proxy requests where the service ID is in the URL path.
 *
 * <p>Pass-through mode allows requests to be routed directly to registered services
 * using the URL pattern {@code /{serviceId}/...}. This provides a simpler alternative
 * to gateway mode for services that don't need complex route matching.
 *
 * <p>The service validates that the service ID is not a reserved path (admin, gateway, q),
 * resolves visibility and authentication requirements, and prepares the proxy plan.
 *
 * <p>All operations are fully reactive and never block.
 */
@ApplicationScoped
public class PassThroughService implements PassThroughUseCase {

    /**
     * Paths that cannot be used as service IDs because they conflict with gateway endpoints:
     * - "admin": Admin API for service registration/management
     * - "gateway": Explicit route-based proxying endpoint
     * - "q": Quarkus built-in endpoints (health, metrics, dev-ui)
     */
    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");

    private final ServiceRegistry serviceRegistry;
    private final ProxyRequestPreparer requestPreparer;
    private final VisibilityResolver visibilityResolver;
    private final EndpointMatcher endpointMatcher;
    private final RouteAuthenticationService routeAuthService;

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            VisibilityResolver visibilityResolver,
            EndpointMatcher endpointMatcher,
            RouteAuthenticationService routeAuthService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.visibilityResolver = visibilityResolver;
        this.endpointMatcher = endpointMatcher;
        this.routeAuthService = routeAuthService;
    }

    @Override
    public Uni<ProxyPlan> prepare(String serviceId, GatewayRequest request) {

        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            var result = new GatewayResult.ReservedPath(serviceId);
            return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
        }

        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                var result = new GatewayResult.ServiceNotFound(serviceId);
                return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
            }

            var service = serviceOpt.get();
            var routeMatch = createRouteMatch(service, request.path(), request.method());

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

    private RouteMatch createRouteMatch(ServiceRegistration service, String targetPath, String method) {
        // First, check if there's a matching endpoint config
        var matchedEndpoint = endpointMatcher.match(targetPath, method, service);

        if (matchedEndpoint.isPresent()) {
            return new RouteMatch(service, matchedEndpoint.get(), targetPath, Map.of());
        }

        // No matching endpoint - create a catch-all with visibility rules and default auth
        var visibility = visibilityResolver.resolve(targetPath, method, service);
        var catchAllEndpoint =
                new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty(), service.defaultAuthRequired());
        return new RouteMatch(service, catchAllEndpoint, targetPath, Map.of());
    }
}
