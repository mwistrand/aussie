package aussie.core.model.auth;

import java.time.Instant;
import java.util.Set;

/**
 * Represents a group that maps to a set of permissions.
 *
 * <p>Groups are used for role-based access control (RBAC). When a token contains
 * group claims, the gateway expands those groups to their associated permissions
 * at validation time. This allows centralized permission management without
 * regenerating tokens.
 *
 * @param id          unique identifier (e.g., "platform-team", "service-admin")
 * @param displayName human-readable name (e.g., "Platform Team")
 * @param description optional description of this group's purpose
 * @param permissions set of permission strings granted to members of this group
 * @param createdAt   when the group was created
 * @param updatedAt   when the group was last modified
 */
public record Group(
        String id,
        String displayName,
        String description,
        Set<String> permissions,
        Instant createdAt,
        Instant updatedAt) {

    public Group {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Group ID cannot be null or blank");
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
     * Create a new group with the given ID, display name, and permissions.
     *
     * @param id          unique identifier
     * @param displayName human-readable name
     * @param permissions set of permissions
     * @return a new Group instance
     */
    public static Group create(String id, String displayName, Set<String> permissions) {
        final var now = Instant.now();
        return new Group(id, displayName, null, permissions, now, now);
    }

    /**
     * Create a copy of this group with updated permissions.
     *
     * @param newPermissions the new set of permissions
     * @return a new Group with updated permissions and updatedAt timestamp
     */
    public Group withPermissions(Set<String> newPermissions) {
        return new Group(id, displayName, description, newPermissions, createdAt, Instant.now());
    }

    /**
     * Create a copy of this group with updated display name and description.
     *
     * @param newDisplayName the new display name
     * @param newDescription the new description
     * @return a new Group with updated fields and updatedAt timestamp
     */
    public Group withDetails(String newDisplayName, String newDescription) {
        return new Group(id, newDisplayName, newDescription, permissions, createdAt, Instant.now());
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

        public Group build() {
            return new Group(id, displayName, description, permissions, createdAt, updatedAt);
        }
    }
}
