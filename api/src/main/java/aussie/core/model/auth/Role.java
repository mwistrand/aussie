package aussie.core.model.auth;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a role that maps to a set of permissions.
 *
 * <p>Roles are used for role-based access control (RBAC). When a token contains
 * role claims, the gateway expands those roles to their associated permissions
 * at validation time. This allows centralized permission management without
 * regenerating tokens.
 *
 * @param id          unique identifier (e.g., "platform-team", "demo-service.admin")
 * @param displayName human-readable name (e.g., "Platform Team")
 * @param description optional description of this role's purpose
 * @param permissions set of permission strings granted to members of this role
 * @param createdAt   when the role was created
 * @param updatedAt   when the role was last modified
 */
public record Role(
        String id,
        String displayName,
        String description,
        Set<String> permissions,
        Instant createdAt,
        Instant updatedAt) {

    public Role {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Role ID cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = id;
        }
        if (description == null) {
            description = "";
        }
        if (permissions == null) {
            permissions = Set.of();
        } else {
            permissions = Set.copyOf(permissions);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    /**
     * Create a new role with the given ID, display name, and permissions.
     *
     * @param id          unique identifier
     * @param displayName human-readable name
     * @param permissions set of permissions
     * @return a new Role instance
     */
    public static Role create(String id, String displayName, Set<String> permissions) {
        final var now = Instant.now();
        return new Role(id, displayName, null, permissions, now, now);
    }

    /**
     * Create a copy of this role with updated permissions.
     *
     * @param newPermissions the new set of permissions
     * @return a new Role with updated permissions and updatedAt timestamp
     */
    public Role withPermissions(Set<String> newPermissions) {
        return new Role(id, displayName, description, newPermissions, createdAt, Instant.now());
    }

    /**
     * Create a copy of this role with updated display name and description.
     *
     * @param newDisplayName the new display name
     * @param newDescription the new description
     * @return a new Role with updated fields and updatedAt timestamp
     */
    public Role withDetails(String newDisplayName, String newDescription) {
        return new Role(id, newDisplayName, newDescription, permissions, createdAt, Instant.now());
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private String displayName;
        private String description;
        private Set<String> permissions = Set.of();
        private Instant createdAt;
        private Instant updatedAt;

        private Builder(String id) {
            this.id = id;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
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

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Role build() {
            return new Role(id, displayName, description, permissions, createdAt, updatedAt);
        }
    }
}
