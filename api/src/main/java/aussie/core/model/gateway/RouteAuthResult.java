package aussie.core.model.gateway;

import java.util.Optional;

import aussie.core.model.auth.AussieToken;

/**
 * Result of route authentication evaluation.
 */
public sealed interface RouteAuthResult {

    /**
     * Request was authenticated. Contains the Aussie-signed token to forward to backend.
     *
     * @param token         the signed token to include in the Authorization header
     * @param authSessionId the session ID if session-based auth was used (for logout tracking)
     */
    record Authenticated(AussieToken token, Optional<String> authSessionId) implements RouteAuthResult {

        /**
         * Convenience constructor for non-session authentication.
         */
        public Authenticated(AussieToken token) {
            this(token, Optional.empty());
        }
    }

    /**
     * Route does not require authentication. Request can proceed without a token.
     */
    record NotRequired() implements RouteAuthResult {}

    /**
     * Authentication failed (invalid/expired token or no token when required).
     *
     * @param reason description of why authentication failed
     */
    record Unauthorized(String reason) implements RouteAuthResult {}

    /**
     * Authentication succeeded but user lacks permission for this route.
     *
     * @param reason description of why access was denied
     */
    record Forbidden(String reason) implements RouteAuthResult {}

    /**
     * Request is malformed (e.g., both bearer token and session cookie present).
     *
     * @param reason description of why the request is invalid
     */
    record BadRequest(String reason) implements RouteAuthResult {}
}
