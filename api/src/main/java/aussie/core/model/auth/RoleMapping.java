package aussie.core.model.auth;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a mapping from role IDs to their associated permissions.
 *
 * <p>This is used at token validation time to expand role claims into
 * effective permissions. The mapping is typically cached and refreshed
 * when roles are modified.
 *
 * @param roleToPermissions map from role ID to set of permissions
 */
public record RoleMapping(Map<String, Set<String>> roleToPermissions) {

    public RoleMapping {
        if (roleToPermissions == null) {
            roleToPermissions = Map.of();
        } else {
            roleToPermissions = Map.copyOf(roleToPermissions);
        }
    }

    /**
     * Create an empty role mapping.
     *
     * @return an empty RoleMapping
     */
    public static RoleMapping empty() {
        return new RoleMapping(Map.of());
    }

    /**
     * Expand a set of roles to their associated permissions.
     *
     * <p>Unknown roles are silently ignored (return empty permissions).
     *
     * @param roles set of role IDs to expand
     * @return set of all permissions granted by the roles
     */
    public Set<String> expandRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }

        return roles.stream()
                .flatMap(role -> roleToPermissions.getOrDefault(role, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Get effective permissions by combining role expansion with direct permissions.
     *
     * @param roles             set of role IDs to expand
     * @param directPermissions set of permissions granted directly (not via roles)
     * @return combined set of all effective permissions
     */
    public Set<String> getEffectivePermissions(Set<String> roles, Set<String> directPermissions) {
        final var expandedPermissions = expandRoles(roles);
        final var direct = directPermissions != null ? directPermissions : Set.<String>of();

        return Stream.concat(expandedPermissions.stream(), direct.stream()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Check if a role exists in this mapping.
     *
     * @param roleId the role ID to check
     * @return true if the role exists
     */
    public boolean hasRole(String roleId) {
        return roleToPermissions.containsKey(roleId);
    }

    /**
     * Get the number of roles in this mapping.
     *
     * @return the number of roles
     */
    public int size() {
        return roleToPermissions.size();
    }
}
