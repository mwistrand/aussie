package aussie.core.service.auth;

/**
 * Exception thrown when a JWKS fetch fails.
 *
 * <p>This can occur due to a network timeout, HTTP error, or malformed JWKS response.
 */
public class JwksFetchException extends RuntimeException {

    public JwksFetchException(String message) {
        super(message);
    }

    public JwksFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
