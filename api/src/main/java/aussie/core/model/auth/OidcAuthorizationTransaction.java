package aussie.core.model.auth;

import java.time.Instant;

/** One-time server-owned state for an OIDC authorization-code exchange. */
public record OidcAuthorizationTransaction(
        String providerId,
        String redirectUri,
        String codeChallenge,
        String nonce,
        ClientType clientType,
        Instant createdAt,
        Instant expiresAt) {

    public OidcAuthorizationTransaction {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("Redirect URI is required");
        }
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new IllegalArgumentException("PKCE challenge is required");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("OIDC nonce is required");
        }
        if (clientType == null) {
            throw new IllegalArgumentException("OIDC client type is required");
        }
        if (createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Transaction expiration must follow creation");
        }
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public enum ClientType {
        PUBLIC,
        SESSION
    }
}
