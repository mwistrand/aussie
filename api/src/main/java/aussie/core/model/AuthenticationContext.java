package aussie.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Contains information about an authenticated request, including the principal,
 * their permissions, and session metadata.
 *
 * @param principal       the authenticated identity
 * @param permissions     set of permission strings granted to this principal
 * @param claims          additional claims/metadata from the authentication source
 * @param authenticatedAt when authentication occurred
 * @param expiresAt       when this authentication expires (null = never)
 */
public record AuthenticationContext(
        Principal principal,
        Set<String> permissions,
        Map<String, Object> claims,
        Instant authenticatedAt,
        Instant expiresAt) {

    public AuthenticationContext {
        if (principal == null) {
            throw new IllegalArgumentException("Principal cannot be null");
        }
        if (permissions == null) {
            permissions = Set.of();
        }
        if (claims == null) {
            claims = Map.of();
        }
        if (authenticatedAt == null) {
            authenticatedAt = Instant.now();
        }
    }

    /**
     * Checks if this principal has the specified permission.
     * The wildcard permission "*" grants access to everything.
     *
     * @param permission the permission to check
     * @return true if the principal has the permission
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission) || permissions.contains("*");
    }

    /**
     * Checks if this authentication context has expired.
     *
     * @return true if expired, false if still valid or never expires
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Creates a builder for constructing an AuthenticationContext.
     */
    public static Builder builder(Principal principal) {
        return new Builder(principal);
    }

    public static class Builder {
        private final Principal principal;
        private Set<String> permissions = Set.of();
        private Map<String, Object> claims = Map.of();
        private Instant authenticatedAt = Instant.now();
        private Instant expiresAt;

        private Builder(Principal principal) {
            this.principal = principal;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder claims(Map<String, Object> claims) {
            this.claims = claims;
            return this;
        }

        public Builder authenticatedAt(Instant authenticatedAt) {
            this.authenticatedAt = authenticatedAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public AuthenticationContext build() {
            return new AuthenticationContext(principal, permissions, claims, authenticatedAt, expiresAt);
        }
    }
}
