package aussie.adapter.in.websocket;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * Vert.x route filter that intercepts WebSocket upgrade requests before JAX-RS.
 *
 * <p>WebSocket upgrade requests arrive as HTTP GET requests with special headers.
 * This filter detects them and routes them to the WebSocket gateway handler,
 * keeping HTTP and WebSocket paths cleanly separated.
 *
 * <p>Priority 50 ensures this runs after CORS (100) but before most other processing.
 */
@ApplicationScoped
public class WebSocketUpgradeFilter {

    private static final Logger LOG = Logger.getLogger(WebSocketUpgradeFilter.class);

    private static final Set<String> RESERVED_PATHS = Set.of("admin", "q");

    private final WebSocketGateway webSocketGateway;

    @Inject
    public WebSocketUpgradeFilter(WebSocketGateway webSocketGateway) {
        this.webSocketGateway = webSocketGateway;
    }

    /**
     * Intercept WebSocket upgrade requests and route to WebSocket handler.
     */
    @RouteFilter(50)
    void interceptWebSocketUpgrade(RoutingContext ctx) {
        if (!isWebSocketUpgrade(ctx.request())) {
            ctx.next();
            return;
        }

        var path = ctx.request().path();
        LOG.debugv("WebSocket upgrade request detected: {0}", path);

        if (path.toLowerCase().startsWith("/gateway/")) {
            // Gateway mode: /gateway/ws/echo -> find by route
            webSocketGateway.handleGatewayUpgrade(ctx);
        } else if (!isReservedPath(path)) {
            // Pass-through mode: /demo-service/ws/echo -> use serviceId
            webSocketGateway.handlePassThroughUpgrade(ctx);
        } else {
            // Reserved path - let it 404 via normal routing
            LOG.debugv("WebSocket upgrade to reserved path: {0}", path);
            ctx.next();
        }
    }

    private boolean isWebSocketUpgrade(HttpServerRequest request) {
        var upgrade = request.getHeader("Upgrade");
        var connection = request.getHeader("Connection");

        return "websocket".equalsIgnoreCase(upgrade)
                && connection != null
                && connection.toLowerCase().contains("upgrade");
    }

    private boolean isReservedPath(String path) {
        // Extract first path segment
        var normalized = path.startsWith("/") ? path.substring(1) : path;
        var slashIndex = normalized.indexOf('/');
        var firstSegment = slashIndex > 0 ? normalized.substring(0, slashIndex) : normalized;

        return RESERVED_PATHS.contains(firstSegment.toLowerCase());
    }
}
