package aussie.core.service.gateway;

import java.net.URI;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Service that handles WebSocket upgrade requests.
 *
 * <p>Supports both gateway mode (route-based) and pass-through mode (service ID based).
 * Connection rate limiting is handled by {@code WebSocketRateLimitFilter} before
 * requests reach this service.
 *
 * <p>All operations are fully reactive and never block.
 */
@ApplicationScoped
public class WebSocketGatewayService implements WebSocketGatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final RouteAuthenticationService routeAuthService;

    @Inject
    public WebSocketGatewayService(ServiceRegistry serviceRegistry, RouteAuthenticationService routeAuthService) {
        this.serviceRegistry = serviceRegistry;
        this.routeAuthService = routeAuthService;
    }

    @Override
    public Uni<WebSocketUpgradeResult> upgradeGateway(WebSocketUpgradeRequest request) {
        // Use async route lookup to ensure cache freshness in multi-instance deployments
        return serviceRegistry.findRouteAsync(request.path(), "GET").flatMap(routeResultOpt -> {
            if (routeResultOpt.isEmpty()) {
                return Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound(request.path()));
            }

            // WebSocket gateway requires a RouteMatch (with endpoint) to proceed
            if (!(routeResultOpt.get() instanceof RouteMatch route)) {
                return Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound(request.path()));
            }

            // Verify this is a WebSocket endpoint
            if (route.endpointConfig().type() != EndpointType.WEBSOCKET) {
                return Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket(request.path()));
            }

            // Connection rate limiting is handled by WebSocketRateLimitFilter
            // Proceed directly to authentication
            return authenticateAndPrepare(request, route);
        });
    }

    @Override
    public Uni<WebSocketUpgradeResult> upgradePassThrough(String serviceId, WebSocketUpgradeRequest request) {
        return serviceRegistry
                .findServiceRouteAsync(serviceId, request.path(), "GET")
                .flatMap(routeResultOpt -> {
                    if (routeResultOpt.isEmpty()) {
                        return Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound(serviceId));
                    }

                    if (!(routeResultOpt.get() instanceof RouteMatch route)
                            || route.endpointConfig().type() != EndpointType.WEBSOCKET) {
                        return Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket(request.path()));
                    }

                    // Connection rate limiting is handled by WebSocketRateLimitFilter
                    return authenticateAndPrepare(request, route);
                });
    }

    // -------------------------------------------------------------------------
    // Authentication and Backend URI
    // -------------------------------------------------------------------------

    private Uni<WebSocketUpgradeResult> authenticateAndPrepare(WebSocketUpgradeRequest request, RouteMatch route) {

        // Convert to GatewayRequest for auth service compatibility
        var gatewayRequest = new GatewayRequest(
                "GET", request.path(), request.headers(), request.requestUri(), null, request.clientIp());

        return routeAuthService.authenticate(gatewayRequest, route).map(authResult -> switch (authResult) {
            case RouteAuthResult.Authenticated auth -> new WebSocketUpgradeResult.Authorized(
                    route,
                    Optional.of(auth.token()),
                    buildBackendUri(route, request.requestUri()),
                    auth.authSessionId());
            case RouteAuthResult.NotRequired nr -> new WebSocketUpgradeResult.Authorized(
                    route, Optional.empty(), buildBackendUri(route, request.requestUri()), Optional.empty());
            case RouteAuthResult.Unauthorized u -> new WebSocketUpgradeResult.Unauthorized(u.reason());
            case RouteAuthResult.Forbidden f -> new WebSocketUpgradeResult.Forbidden(f.reason());
            case RouteAuthResult.BadRequest b -> new WebSocketUpgradeResult.Unauthorized(b.reason());
        });
    }

    private URI buildBackendUri(RouteMatch route, URI requestUri) {
        // Convert HTTP URI to WebSocket URI (http->ws, https->wss)
        final var baseUrl = route.service().baseUrl();
        final var scheme = "https".equals(baseUrl.getScheme()) ? "wss" : "ws";
        final var port = baseUrl.getPort();
        final var portSuffix = (port == -1 || port == 80 || port == 443) ? "" : ":" + port;
        final var path = route.targetPath().startsWith("/") ? route.targetPath() : "/" + route.targetPath();
        final var query = requestUri.getRawQuery();

        return URI.create(scheme + "://" + baseUrl.getHost() + portSuffix + path + (query == null ? "" : "?" + query));
    }
}
