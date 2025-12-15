package aussie.core.model.websocket;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Represents a WebSocket upgrade request to be authenticated and routed.
 */
public record WebSocketUpgradeRequest(String path, Map<String, List<String>> headers, URI requestUri) {}
