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
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.port.in.PassThroughUseCase;
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
    private final VisibilityResolver visibilityResolver;
    private final RouteAuthenticationService routeAuthService;
    private final ProxyPlanBuilder proxyPlanBuilder;

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry,
            VisibilityResolver visibilityResolver,
            RouteAuthenticationService routeAuthService,
            ProxyPlanBuilder proxyPlanBuilder) {
        this.serviceRegistry = serviceRegistry;
        this.visibilityResolver = visibilityResolver;
        this.routeAuthService = routeAuthService;
        this.proxyPlanBuilder = proxyPlanBuilder;
    }

    @Override
    public Uni<ProxyPlan> prepare(String serviceId, GatewayRequest request) {

        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            var result = new GatewayResult.ReservedPath(serviceId);
            return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
        }

        return serviceRegistry
                .findServiceRouteAsync(serviceId, request.path(), request.method())
                .flatMap(routeOpt -> {
                    if (routeOpt.isEmpty()) {
                        var result = new GatewayResult.ServiceNotFound(serviceId);
                        return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
                    }

                    var routeMatch = toRouteMatch(routeOpt.get(), request.path(), request.method());

                    return routeAuthService
                            .authenticate(request, routeMatch)
                            .map(authResult -> proxyPlanBuilder.build(request, routeMatch, authResult));
                });
    }

    private RouteMatch toRouteMatch(RouteLookupResult route, String targetPath, String method) {
        if (route instanceof RouteMatch routeMatch) {
            return routeMatch;
        }

        var service = ((ServiceOnlyMatch) route).service();
        var visibility = visibilityResolver.resolve(targetPath, method, service);
        var catchAllEndpoint =
                new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty(), service.defaultAuthRequired());
        return new RouteMatch(service, catchAllEndpoint, targetPath, Map.of());
    }
}
