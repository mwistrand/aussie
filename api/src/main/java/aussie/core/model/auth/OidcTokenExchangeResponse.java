package aussie.core.model.auth;

import java.util.Map;
import java.util.Optional;

/**
 * Response from OIDC token exchange.
 *
 * <p>Contains the tokens and metadata returned by the identity provider
 * after a successful authorization code exchange.
 *
 * @param accessToken The access token (always present on success)
 * @param idToken The ID token (present when openid scope requested)
 * @param refreshToken The refresh token (present if offline_access granted)
 * @param tokenType Token type, typically "Bearer"
 * @param expiresIn Seconds until access token expires
 * @param scope Granted scopes (space-separated, may differ from requested)
 * @param additionalClaims Any additional fields from the token response
 */
public record OidcTokenExchangeResponse(
        String accessToken,
        Optional<String> idToken,
        Optional<String> refreshToken,
        String tokenType,
        long expiresIn,
        Optional<String> scope,
        Map<String, Object> additionalClaims) {

    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;

    public OidcTokenExchangeResponse {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token is required");
        }
        if (tokenType == null || tokenType.isBlank()) {
            tokenType = "Bearer";
        }
        if (expiresIn <= 0) {
            expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        }
        if (idToken == null) {
            idToken = Optional.empty();
        }
        if (refreshToken == null) {
            refreshToken = Optional.empty();
        }
        if (scope == null) {
            scope = Optional.empty();
        }
        if (additionalClaims == null) {
            additionalClaims = Map.of();
        }
    }

    /**
     * Check if this response contains an ID token.
     *
     * @return true if ID token is present
     */
    public boolean hasIdToken() {
        return idToken.isPresent();
    }

    /**
     * Check if this response contains a refresh token.
     *
     * @return true if refresh token is present
     */
    public boolean hasRefreshToken() {
        return refreshToken.isPresent();
    }
}
