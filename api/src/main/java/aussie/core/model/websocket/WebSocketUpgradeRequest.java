package aussie.core.model.websocket;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import aussie.core.model.routing.RouteLookupResult;

/**
 * Represents a WebSocket upgrade request to be authenticated and routed.
 */
public record WebSocketUpgradeRequest(
        String path,
        Map<String, List<String>> headers,
        URI requestUri,
        String clientIp,
        Optional<RouteLookupResult> resolvedRoute,
        boolean hasRouteSnapshot) {

    public WebSocketUpgradeRequest(String path, Map<String, List<String>> headers, URI requestUri, String clientIp) {
        this(path, headers, requestUri, clientIp, Optional.empty(), false);
    }

    public WebSocketUpgradeRequest {
        if (resolvedRoute == null) {
            resolvedRoute = Optional.empty();
        }
        if (resolvedRoute.isPresent()) {
            hasRouteSnapshot = true;
        }
    }
}
