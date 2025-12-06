package aussie.adapter.in.auth;

import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.Permissions;

/**
 * Maps Aussie API key permissions to Quarkus Security roles.
 *
 * <p>Permission format: {@code resource:action} (e.g., {@code admin:read})
 * <p>Role format: {@code resource-action} (e.g., {@code admin-read})
 *
 * <p>The wildcard permission {@code *} grants all roles.
 *
 * <p>Example mappings:
 * <ul>
 *   <li>{@code admin:read} → {@code admin-read}</li>
 *   <li>{@code admin:write} → {@code admin-write}</li>
 *   <li>{@code *} → {@code admin} (full admin role)</li>
 * </ul>
 */
@ApplicationScoped
public class PermissionRoleMapper {

    /**
     * Role constant for read access to admin endpoints.
     */
    public static final String ROLE_ADMIN_READ = "admin-read";

    /**
     * Role constant for write access to admin endpoints.
     */
    public static final String ROLE_ADMIN_WRITE = "admin-write";

    /**
     * Role constant for full admin access.
     */
    public static final String ROLE_ADMIN = "admin";

    /**
     * Converts a set of Aussie permissions to Quarkus Security roles.
     *
     * @param permissions the set of permissions (e.g., {"admin:read", "admin:write"})
     * @return the corresponding set of roles (e.g., {"admin-read", "admin-write"})
     */
    public Set<String> toRoles(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }

        Set<String> roles = new HashSet<>();

        for (String permission : permissions) {
            if (Permissions.ALL.equals(permission)) {
                // Wildcard grants full admin role
                roles.add(ROLE_ADMIN);
                roles.add(ROLE_ADMIN_READ);
                roles.add(ROLE_ADMIN_WRITE);
            } else {
                // Convert colon to hyphen: admin:read -> admin-read
                String role = permission.replace(':', '-');
                roles.add(role);
            }
        }

        return roles;
    }

    /**
     * Converts a single Aussie permission to a Quarkus Security role.
     *
     * @param permission the permission (e.g., "admin:read")
     * @return the corresponding role (e.g., "admin-read")
     */
    public String toRole(String permission) {
        if (permission == null) {
            return null;
        }
        if (Permissions.ALL.equals(permission)) {
            return ROLE_ADMIN;
        }
        return permission.replace(':', '-');
    }
}
