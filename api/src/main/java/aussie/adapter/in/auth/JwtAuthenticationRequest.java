package aussie.adapter.in.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * Authentication request containing a JWT token for validation.
 *
 * <p>Used by {@link JwtIdentityProvider} to validate JWT tokens from
 * configured OIDC providers.
 */
public class JwtAuthenticationRequest extends BaseAuthenticationRequest {

    private final String token;

    public JwtAuthenticationRequest(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}
