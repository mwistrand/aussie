package aussie.core.model.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configuration for an external token provider (OIDC/JWKS).
 *
 * @param id                  unique identifier for this provider (e.g., "auth0", "okta")
 * @param issuer              expected iss claim value
 * @param jwksUri             JWKS endpoint for key retrieval
 * @param discoveryUri        optional OIDC discovery endpoint
 * @param audiences           allowed aud claim values
 * @param keyRefreshInterval  how often to refresh JWKS (default 1 hour)
 * @param claimsMapping       map external claim names to internal claim names
 */
public record TokenProviderConfig(
        String id,
        String issuer,
        URI jwksUri,
        Optional<URI> discoveryUri,
        Set<String> audiences,
        Duration keyRefreshInterval,
        Map<String, String> claimsMapping) {

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
        if (discoveryUri == null) {
            discoveryUri = Optional.empty();
        }
        if (audiences == null) {
            audiences = Set.of();
        }
        if (keyRefreshInterval == null) {
            keyRefreshInterval = Duration.ofHours(1);
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
        private URI discoveryUri;
        private Set<String> audiences = Set.of();
        private Duration keyRefreshInterval = Duration.ofHours(1);
        private Map<String, String> claimsMapping = Map.of();

        private Builder(String id, String issuer, URI jwksUri) {
            this.id = id;
            this.issuer = issuer;
            this.jwksUri = jwksUri;
        }

        public Builder discoveryUri(URI discoveryUri) {
            this.discoveryUri = discoveryUri;
            return this;
        }

        public Builder audiences(Set<String> audiences) {
            this.audiences = audiences;
            return this;
        }

        public Builder keyRefreshInterval(Duration interval) {
            this.keyRefreshInterval = interval;
            return this;
        }

        public Builder claimsMapping(Map<String, String> mapping) {
            this.claimsMapping = mapping;
            return this;
        }

        public TokenProviderConfig build() {
            return new TokenProviderConfig(
                    id,
                    issuer,
                    jwksUri,
                    Optional.ofNullable(discoveryUri),
                    audiences,
                    keyRefreshInterval,
                    claimsMapping);
        }
    }
}
