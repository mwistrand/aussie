package aussie.core.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.AussieToken;
import aussie.core.model.EndpointConfig;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.in.PassThroughUseCase;
import aussie.core.port.out.ProxyClient;

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
    private final ProxyClient proxyClient;
    private final VisibilityResolver visibilityResolver;
    private final EndpointMatcher endpointMatcher;
    private final RouteAuthenticationService routeAuthService;

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            VisibilityResolver visibilityResolver,
            EndpointMatcher endpointMatcher,
            RouteAuthenticationService routeAuthService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.visibilityResolver = visibilityResolver;
        this.endpointMatcher = endpointMatcher;
        this.routeAuthService = routeAuthService;
    }

    @Override
    public Uni<GatewayResult> forward(String serviceId, GatewayRequest request) {
        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            return Uni.createFrom().item(new GatewayResult.ReservedPath(serviceId));
        }

        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                return Uni.createFrom().item(new GatewayResult.ServiceNotFound(serviceId));
            }

            var service = serviceOpt.get();
            var routeMatch = createRouteMatch(service, request.path(), request.method());

            return routeAuthService
                    .authenticate(request, routeMatch)
                    .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch));
        });
    }

    private Uni<GatewayResult> handleAuthResult(
            RouteAuthResult authResult, GatewayRequest request, RouteMatch routeMatch) {
        return switch (authResult) {
            case RouteAuthResult.Authenticated auth -> forwardWithToken(request, routeMatch, auth.token());
            case RouteAuthResult.NotRequired notRequired -> forwardWithoutToken(request, routeMatch);
            case RouteAuthResult.Unauthorized unauthorized -> Uni.createFrom()
                    .item(new GatewayResult.Unauthorized(unauthorized.reason()));
            case RouteAuthResult.Forbidden forbidden -> Uni.createFrom()
                    .item(new GatewayResult.Forbidden(forbidden.reason()));
            case RouteAuthResult.BadRequest badRequest -> Uni.createFrom()
                    .item(new GatewayResult.BadRequest(badRequest.reason()));
        };
    }

    private Uni<GatewayResult> forwardWithToken(GatewayRequest request, RouteMatch routeMatch, AussieToken token) {
        Optional<AussieToken> tokenOpt = token.hasToken() ? Optional.of(token) : Optional.empty();
        var preparedRequest = requestPreparer.prepare(request, routeMatch, tokenOpt);

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
    }

    private Uni<GatewayResult> forwardWithoutToken(GatewayRequest request, RouteMatch routeMatch) {
        var preparedRequest = requestPreparer.prepare(request, routeMatch, Optional.empty());

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
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
