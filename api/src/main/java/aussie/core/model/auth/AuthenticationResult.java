package aussie.core.model.auth;

/**
 * Represents the result of an authentication attempt.
 *
 * This is a sealed interface with three possible outcomes:
 * - Success: authentication succeeded, contains the AuthenticationContext
 * - Failure: authentication failed (invalid credentials, expired token, etc.)
 * - Skip: the provider doesn't handle this type of authentication
 */
public sealed interface AuthenticationResult {

    /**
     * Authentication succeeded.
     *
     * @param context the authentication context with principal and permissions
     */
    record Success(AuthenticationContext context) implements AuthenticationResult {
        public Success {
            if (context == null) {
                throw new IllegalArgumentException("AuthenticationContext cannot be null");
            }
        }
    }

    /**
     * Authentication failed with a specific reason.
     *
     * @param reason     human-readable description of why authentication failed
     * @param statusCode HTTP status code to return (401 for unauthorized, 403 for forbidden)
     */
    record Failure(String reason, int statusCode) implements AuthenticationResult {
        public Failure {
            if (reason == null || reason.isBlank()) {
                reason = "Authentication failed";
            }
            if (statusCode < 400 || statusCode >= 500) {
                statusCode = 401;
            }
        }

        public static Failure unauthorized(String reason) {
            return new Failure(reason, 401);
        }

        public static Failure forbidden(String reason) {
            return new Failure(reason, 403);
        }
    }

    /**
     * The provider doesn't handle this type of request.
     * The authentication filter should try the next provider.
     */
    record Skip() implements AuthenticationResult {
        private static final Skip INSTANCE = new Skip();

        public static Skip instance() {
            return INSTANCE;
        }
    }
}
