package aussie.core.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointType;
import aussie.core.model.GatewayRequest;
import aussie.core.model.RateLimitDecision;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.WebSocketUpgradeRequest;
import aussie.core.model.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;

/**
 * Service that handles WebSocket upgrade requests.
 *
 * <p>Supports both gateway mode (route-based) and pass-through mode (service ID based).
 * All operations are fully reactive and never block.
 */
@ApplicationScoped
public class WebSocketGatewayService implements WebSocketGatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final RouteAuthenticationService routeAuthService;
    private final EndpointMatcher endpointMatcher;
    private final WebSocketRateLimitService rateLimitService;

    @Inject
    public WebSocketGatewayService(
            ServiceRegistry serviceRegistry,
            RouteAuthenticationService routeAuthService,
            EndpointMatcher endpointMatcher,
            WebSocketRateLimitService rateLimitService) {
        this.serviceRegistry = serviceRegistry;
        this.routeAuthService = routeAuthService;
        this.endpointMatcher = endpointMatcher;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public Uni<WebSocketUpgradeResult> upgradeGateway(WebSocketUpgradeRequest request) {
        // Find route by path pattern (like GatewayService) - synchronous lookup on local cache
        final var routeResultOpt = serviceRegistry.findRoute(request.path(), "GET");

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

        final var serviceId = route.service().serviceId();
        final var clientId = extractClientId(request);

        // Check rate limit before proceeding with authentication
        return checkRateLimitAndProceed(request, route, serviceId, clientId);
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

            final var clientId = extractClientId(request);
            return checkRateLimitAndProceed(request, routeMatch.get(), serviceId, clientId);
        });
    }

    private Optional<RouteMatch> findWebSocketEndpoint(aussie.core.model.ServiceRegistration service, String path) {
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
    // Rate Limiting
    // -------------------------------------------------------------------------

    private Uni<WebSocketUpgradeResult> checkRateLimitAndProceed(
            WebSocketUpgradeRequest request, RouteMatch route, String serviceId, String clientId) {

        return rateLimitService
                .checkConnectionLimit(serviceId, clientId)
                .flatMap(decision -> handleRateLimitDecision(decision, request, route));
    }

    private Uni<WebSocketUpgradeResult> handleRateLimitDecision(
            RateLimitDecision decision, WebSocketUpgradeRequest request, RouteMatch route) {

        if (!decision.allowed()) {
            return Uni.createFrom()
                    .item(new WebSocketUpgradeResult.RateLimited(
                            decision.retryAfterSeconds(), decision.limit(), decision.resetAtEpochSeconds()));
        }
        return authenticateAndPrepare(request, route);
    }

    // -------------------------------------------------------------------------
    // Client Identification (priority: session > auth header > api key > IP)
    // -------------------------------------------------------------------------

    private String extractClientId(WebSocketUpgradeRequest request) {
        return extractSessionId(request)
                .or(() -> extractAuthHeaderHash(request))
                .or(() -> extractApiKeyId(request))
                .orElseGet(() -> extractClientIp(request));
    }

    private Optional<String> extractSessionId(WebSocketUpgradeRequest request) {
        // Check for session ID in headers (cookies parsed as Cookie header)
        final var cookieHeader = getFirstHeader(request, "Cookie");
        if (cookieHeader != null && cookieHeader.contains("aussie_session=")) {
            final var start = cookieHeader.indexOf("aussie_session=") + 15;
            final var end = cookieHeader.indexOf(";", start);
            final var sessionId = end > 0 ? cookieHeader.substring(start, end) : cookieHeader.substring(start);
            return Optional.of("session:" + sessionId);
        }
        final var header = getFirstHeader(request, "X-Session-ID");
        return Optional.ofNullable(header).map(h -> "session:" + h);
    }

    private Optional<String> extractAuthHeaderHash(WebSocketUpgradeRequest request) {
        final var auth = getFirstHeader(request, "Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return Optional.of("bearer:" + hashToken(auth.substring(7)));
        }
        return Optional.empty();
    }

    private Optional<String> extractApiKeyId(WebSocketUpgradeRequest request) {
        return Optional.ofNullable(getFirstHeader(request, "X-API-Key-ID")).map(id -> "apikey:" + id);
    }

    private String extractClientIp(WebSocketUpgradeRequest request) {
        final var forwarded = getFirstHeader(request, "X-Forwarded-For");
        if (forwarded != null) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:unknown";
    }

    private String getFirstHeader(WebSocketUpgradeRequest request, String headerName) {
        final var values = request.headers().get(headerName);
        if (values == null || values.isEmpty()) {
            // Try case-insensitive lookup
            return request.headers().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(headerName))
                    .map(Map.Entry::getValue)
                    .filter(v -> !v.isEmpty())
                    .map(List::getFirst)
                    .findFirst()
                    .orElse(null);
        }
        return values.getFirst();
    }

    private String hashToken(String token) {
        return Integer.toHexString(token.hashCode());
    }

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
