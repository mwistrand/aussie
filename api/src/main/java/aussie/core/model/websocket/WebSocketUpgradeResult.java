package aussie.core.model.websocket;

import java.net.URI;
import java.util.Optional;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.routing.RouteMatch;

/**
 * Result of a WebSocket upgrade attempt.
 */
public sealed interface WebSocketUpgradeResult {

    /**
     * Upgrade authorized - proceed with WebSocket connection.
     *
     * @param route         the matched route
     * @param token         the Aussie token (if authenticated)
     * @param backendUri    the backend WebSocket URI to connect to
     * @param authSessionId the auth session ID (if session-based auth was used)
     */
    record Authorized(RouteMatch route, Optional<AussieToken> token, URI backendUri, Optional<String> authSessionId)
            implements WebSocketUpgradeResult {

        /**
         * Convenience constructor without auth session ID (for non-session auth or no auth).
         */
        public Authorized(RouteMatch route, Optional<AussieToken> token, URI backendUri) {
            this(route, token, backendUri, Optional.empty());
        }
    }

    /**
     * Authentication required but not provided or invalid.
     */
    record Unauthorized(String reason) implements WebSocketUpgradeResult {}

    /**
     * User authenticated but not permitted to access this endpoint.
     */
    record Forbidden(String reason) implements WebSocketUpgradeResult {}

    /**
     * No route matches the requested path.
     */
    record RouteNotFound(String path) implements WebSocketUpgradeResult {}

    /**
     * Service ID not found (pass-through mode).
     */
    record ServiceNotFound(String serviceId) implements WebSocketUpgradeResult {}

    /**
     * Path matches but endpoint is not a WebSocket endpoint.
     */
    record NotWebSocket(String path) implements WebSocketUpgradeResult {}

    /**
     * Connection rate limit exceeded.
     *
     * @param retryAfterSeconds seconds until the client can retry
     * @param limit             the rate limit
     * @param resetAtEpochSeconds Unix timestamp when limit resets
     */
    record RateLimited(long retryAfterSeconds, long limit, long resetAtEpochSeconds)
            implements WebSocketUpgradeResult {}
}
