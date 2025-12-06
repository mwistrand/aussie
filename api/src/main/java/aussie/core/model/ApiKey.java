package aussie.core.model;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a stored API key for authentication.
 *
 * <p>API keys are used for service-to-service authentication. The actual key
 * is never stored; only its SHA-256 hash is persisted for validation.
 *
 * @param id          short identifier for display and revocation (e.g., "k8f3j2a1")
 * @param keyHash     SHA-256 hash of the actual key (never store plaintext)
 * @param name        display name (e.g., "user-service-prod")
 * @param description optional description of this key's purpose
 * @param permissions set of permission strings granted to this key
 * @param createdBy   identifier of the principal who created this key (e.g., key ID or "bootstrap")
 * @param createdAt   when the key was created
 * @param expiresAt   when the key expires (null = never)
 * @param revoked     whether the key has been revoked
 */
public record ApiKey(
        String id,
        String keyHash,
        String name,
        String description,
        Set<String> permissions,
        String createdBy,
        Instant createdAt,
        Instant expiresAt,
        boolean revoked) {

    public ApiKey {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("API key ID cannot be null or blank");
        }
        if (keyHash == null || keyHash.isBlank()) {
            throw new IllegalArgumentException("API key hash cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
        if (description == null) {
            description = "";
        }
        if (permissions == null) {
            permissions = Set.of();
        }
        if (createdBy == null) {
            createdBy = "unknown";
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Checks if this API key is valid (not revoked and not expired).
     *
     * @return true if the key can be used for authentication
     */
    public boolean isValid() {
        return !revoked && (expiresAt == null || Instant.now().isBefore(expiresAt));
    }

    /**
     * Creates a copy of this key with the hash redacted for display purposes.
     *
     * @return a new ApiKey with "[REDACTED]" as the keyHash
     */
    public ApiKey redacted() {
        return new ApiKey(id, "[REDACTED]", name, description, permissions, createdBy, createdAt, expiresAt, revoked);
    }

    /**
     * Creates a revoked copy of this key.
     *
     * @return a new ApiKey with revoked=true
     */
    public ApiKey revoke() {
        return new ApiKey(id, keyHash, name, description, permissions, createdBy, createdAt, expiresAt, true);
    }

    public static Builder builder(String id, String keyHash) {
        return new Builder(id, keyHash);
    }

    public static class Builder {
        private final String id;
        private final String keyHash;
        private String name;
        private String description;
        private Set<String> permissions = Set.of();
        private String createdBy;
        private Instant createdAt = Instant.now();
        private Instant expiresAt;
        private boolean revoked;

        private Builder(String id, String keyHash) {
            this.id = id;
            this.keyHash = keyHash;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder revoked(boolean revoked) {
            this.revoked = revoked;
            return this;
        }

        public ApiKey build() {
            return new ApiKey(id, keyHash, name, description, permissions, createdBy, createdAt, expiresAt, revoked);
        }
    }
}
