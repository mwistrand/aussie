package aussie.core.model.auth;

import java.util.Optional;

/**
 * Request parameters for OIDC token exchange.
 *
 * <p>Contains all information needed to exchange an authorization code
 * for tokens with an identity provider's token endpoint.
 *
 * @param authorizationCode The authorization code received from the IdP
 * @param redirectUri The redirect URI (must match the authorization request)
 * @param codeVerifier Optional PKCE code verifier for S256 challenge verification
 * @param tokenEndpoint The IdP token endpoint URL
 * @param clientId The OAuth2 client ID
 * @param clientSecret The OAuth2 client secret (may be null for public clients)
 * @param clientAuthMethod Client authentication method
 * @param scopes Optional scopes to request (space-separated)
 */
public record OidcTokenExchangeRequest(
        String authorizationCode,
        String redirectUri,
        Optional<String> codeVerifier,
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        ClientAuthMethod clientAuthMethod,
        Optional<String> scopes) {

    public OidcTokenExchangeRequest {
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new IllegalArgumentException("Authorization code is required");
        }
        if (tokenEndpoint == null || tokenEndpoint.isBlank()) {
            throw new IllegalArgumentException("Token endpoint is required");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Client ID is required");
        }
        if (clientAuthMethod == null) {
            clientAuthMethod = ClientAuthMethod.CLIENT_SECRET_BASIC;
        }
        if (codeVerifier == null) {
            codeVerifier = Optional.empty();
        }
        if (scopes == null) {
            scopes = Optional.empty();
        }
    }

    /**
     * Client authentication methods for OAuth2 token endpoint.
     */
    public enum ClientAuthMethod {
        /**
         * HTTP Basic authentication with client_id:client_secret.
         *
         * <p>Credentials are base64-encoded in the Authorization header.
         */
        CLIENT_SECRET_BASIC,

        /**
         * Form-encoded client_id and client_secret in request body.
         *
         * <p>Credentials are included as form parameters.
         */
        CLIENT_SECRET_POST
    }
}
