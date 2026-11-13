package aussie.core.model.auth;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for an external token provider (OIDC/JWKS).
 *
 * @param id                  unique identifier for this provider (e.g., "auth0", "okta")
 * @param issuer              expected iss claim value
 * @param jwksUri             JWKS endpoint for key retrieval
 * @param audiences           allowed aud claim values
 * @param allowedAlgorithms   allowed JWS {@code alg} header values
 * @param claimsMapping       map external claim names to internal claim names
 */
public record TokenProviderConfig(
        String id,
        String issuer,
        URI jwksUri,
        Set<String> audiences,
        Set<String> allowedAlgorithms,
        Map<String, String> claimsMapping) {

    private static final Set<String> DEFAULT_ALGORITHMS =
            Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "EdDSA");

    public TokenProviderConfig {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Provider ID cannot be null or blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer cannot be null or blank");
        }
        if (jwksUri == null) {
            throw new IllegalArgumentException("JWKS URI cannot be null");
        }
        if (audiences == null) {
            audiences = Set.of();
        }
        if (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) {
            allowedAlgorithms = DEFAULT_ALGORITHMS;
        }
        if (claimsMapping == null) {
            claimsMapping = Map.of();
        }
    }

    /**
     * Builder for TokenProviderConfig.
     */
    public static Builder builder(String id, String issuer, URI jwksUri) {
        return new Builder(id, issuer, jwksUri);
    }

    public static class Builder {
        private final String id;
        private final String issuer;
        private final URI jwksUri;
        private Set<String> audiences = Set.of();
        private Set<String> allowedAlgorithms = DEFAULT_ALGORITHMS;
        private Map<String, String> claimsMapping = Map.of();

        private Builder(String id, String issuer, URI jwksUri) {
            this.id = id;
            this.issuer = issuer;
            this.jwksUri = jwksUri;
        }

        public Builder audiences(Set<String> audiences) {
            this.audiences = audiences;
            return this;
        }

        public Builder allowedAlgorithms(Set<String> allowedAlgorithms) {
            this.allowedAlgorithms = allowedAlgorithms;
            return this;
        }

        public Builder claimsMapping(Map<String, String> mapping) {
            this.claimsMapping = mapping;
            return this;
        }

        public TokenProviderConfig build() {
            return new TokenProviderConfig(id, issuer, jwksUri, audiences, allowedAlgorithms, claimsMapping);
        }
    }
}
