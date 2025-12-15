package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;

/**
 * Use case for handling WebSocket upgrade requests.
 */
public interface WebSocketGatewayUseCase {

    /**
     * Handle WebSocket upgrade for gateway mode (/gateway/...).
     * Finds the route by path pattern matching.
     *
     * @param request the upgrade request
     * @return the upgrade result
     */
    Uni<WebSocketUpgradeResult> upgradeGateway(WebSocketUpgradeRequest request);

    /**
     * Handle WebSocket upgrade for pass-through mode (/{serviceId}/...).
     * Looks up the service directly by ID.
     *
     * @param serviceId the service identifier
     * @param request   the upgrade request
     * @return the upgrade result
     */
    Uni<WebSocketUpgradeResult> upgradePassThrough(String serviceId, WebSocketUpgradeRequest request);
}
