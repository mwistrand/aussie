package aussie.core.service.gateway;

import java.net.URI;
import java.util.Map;
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
import aussie.core.service.routing.EndpointMatcher;
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
    private final EndpointMatcher endpointMatcher;

    @Inject
    public WebSocketGatewayService(
            ServiceRegistry serviceRegistry,
            RouteAuthenticationService routeAuthService,
            EndpointMatcher endpointMatcher) {
        this.serviceRegistry = serviceRegistry;
        this.routeAuthService = routeAuthService;
        this.endpointMatcher = endpointMatcher;
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
        // Look up service directly (like PassThroughService) - reactive lookup
        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                return Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound(serviceId));
            }

            final var service = serviceOpt.get();
            final var routeMatch = findWebSocketEndpoint(service, request.path());

            if (routeMatch.isEmpty()) {
                return Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket(request.path()));
            }

            // Connection rate limiting is handled by WebSocketRateLimitFilter
            return authenticateAndPrepare(request, routeMatch.get());
        });
    }

    private Optional<RouteMatch> findWebSocketEndpoint(
            aussie.core.model.service.ServiceRegistration service, String path) {
        // Find matching endpoint that is a WebSocket type
        final var endpointOpt = endpointMatcher.match(path, "GET", service);

        if (endpointOpt.isEmpty()) {
            return Optional.empty();
        }

        final var endpoint = endpointOpt.get();

        // Verify it's a WebSocket endpoint
        if (endpoint.type() != EndpointType.WEBSOCKET) {
            return Optional.empty();
        }

        return Optional.of(new RouteMatch(service, endpoint, path, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Authentication and Backend URI
    // -------------------------------------------------------------------------

    private Uni<WebSocketUpgradeResult> authenticateAndPrepare(WebSocketUpgradeRequest request, RouteMatch route) {

        // Convert to GatewayRequest for auth service compatibility
        var gatewayRequest = new GatewayRequest("GET", request.path(), request.headers(), request.requestUri(), null);

        return routeAuthService.authenticate(gatewayRequest, route).map(authResult -> switch (authResult) {
            case RouteAuthResult.Authenticated auth -> new WebSocketUpgradeResult.Authorized(
                    route, Optional.of(auth.token()), buildBackendUri(route), auth.authSessionId());
            case RouteAuthResult.NotRequired nr -> new WebSocketUpgradeResult.Authorized(
                    route, Optional.empty(), buildBackendUri(route), Optional.empty());
            case RouteAuthResult.Unauthorized u -> new WebSocketUpgradeResult.Unauthorized(u.reason());
            case RouteAuthResult.Forbidden f -> new WebSocketUpgradeResult.Forbidden(f.reason());
            case RouteAuthResult.BadRequest b -> new WebSocketUpgradeResult.Unauthorized(b.reason());
        });
    }

    private URI buildBackendUri(RouteMatch route) {
        // Convert HTTP URI to WebSocket URI (http->ws, https->wss)
        var baseUrl = route.service().baseUrl();
        var scheme = "https".equals(baseUrl.getScheme()) ? "wss" : "ws";
        var port = baseUrl.getPort();
        var portSuffix = (port == -1 || port == 80 || port == 443) ? "" : ":" + port;
        var path = route.targetPath().startsWith("/") ? route.targetPath() : "/" + route.targetPath();

        return URI.create(scheme + "://" + baseUrl.getHost() + portSuffix + path);
    }
}
