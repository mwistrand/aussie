package aussie.core.model.auth;

import java.util.HashSet;
import java.util.Set;

/**
 * Permission enum for Aussie API authentication.
 *
 * <p>Each enum constant encapsulates a permission string value. For use in
 * annotations (which require compile-time constants), use the string constants
 * in {@link Permissions} instead.
 *
 * <p>Permissions follow a hierarchical format and represent operations that can be
 * performed on services and resources. These are the canonical operation names
 * used in permission policies.
 *
 * <p>The wildcard permission {@code *} grants all permissions.
 *
 * <h2>Important Distinction: ADMIN_CLAIM vs ADMIN</h2>
 * <ul>
 * <li>{@link #ADMIN_CLAIM} ({@code "aussie:admin"}) - A token claim value used
 * by {@code DefaultPermissionPolicy} for service-level authorization. Controls
 * which backend services can be accessed through the gateway.</li>
 * <li>{@link #ADMIN} ({@code "admin"}) - A Quarkus Security role used with
 * {@code @RolesAllowed} annotations for endpoint-level authorization. Controls
 * access to admin REST endpoints.</li>
 * </ul>
 *
 * @see Permissions
 */
public enum Permission {

    // ========================================================================
    // Wildcard permission
    // ========================================================================

    /**
     * Wildcard permission that grants access to everything.
     */
    ALL(Permissions.ALL),

    /**
     * Default admin claim used for service-level authorization.
     */
    ADMIN_CLAIM(Permissions.ADMIN_CLAIM),

    // ========================================================================
    // Role constants (used by @RolesAllowed via Permissions class)
    // ========================================================================

    /**
     * Role for full admin access (granted by wildcard permission).
     */
    ADMIN(Permissions.ADMIN),

    /**
     * Role for reading service configurations.
     */
    SERVICE_CONFIG_READ(Permissions.SERVICE_CONFIG_READ),

    /**
     * Role for creating service configurations.
     */
    SERVICE_CONFIG_CREATE(Permissions.SERVICE_CONFIG_CREATE),

    /**
     * Role for updating service configurations.
     */
    SERVICE_CONFIG_UPDATE(Permissions.SERVICE_CONFIG_UPDATE),

    /**
     * Role for deleting service configurations.
     */
    SERVICE_CONFIG_DELETE(Permissions.SERVICE_CONFIG_DELETE),

    /**
     * Role for reading service permission policies.
     */
    SERVICE_PERMISSIONS_READ(Permissions.SERVICE_PERMISSIONS_READ),

    /**
     * Role for writing service permission policies.
     */
    SERVICE_PERMISSIONS_WRITE(Permissions.SERVICE_PERMISSIONS_WRITE),

    /**
     * Role for reading API keys.
     */
    APIKEYS_READ(Permissions.APIKEYS_READ),

    /**
     * Role for writing API keys.
     */
    APIKEYS_WRITE(Permissions.APIKEYS_WRITE),

    // ========================================================================
    // Role management permissions
    // ========================================================================

    /**
     * Permission for creating roles.
     */
    AUTH_ROLES_CREATE(Permissions.AUTH_ROLES_CREATE),

    /**
     * Permission for reading roles.
     */
    AUTH_ROLES_READ(Permissions.AUTH_ROLES_READ),

    /**
     * Permission for updating roles.
     */
    AUTH_ROLES_UPDATE(Permissions.AUTH_ROLES_UPDATE),

    /**
     * Permission for deleting roles.
     */
    AUTH_ROLES_DELETE(Permissions.AUTH_ROLES_DELETE),

    // ========================================================================
    // Service operation constants (for permission policies)
    // ========================================================================

    /**
     * Service configuration create operation.
     */
    CONFIG_CREATE(Permissions.CONFIG_CREATE),

    /**
     * Service configuration read operation.
     */
    CONFIG_READ(Permissions.CONFIG_READ),

    /**
     * Service configuration update operation.
     */
    CONFIG_UPDATE(Permissions.CONFIG_UPDATE),

    /**
     * Service configuration delete operation.
     */
    CONFIG_DELETE(Permissions.CONFIG_DELETE),

    /**
     * Permission policy read operation.
     */
    PERMISSIONS_READ(Permissions.PERMISSIONS_READ),

    /**
     * Permission policy update operation.
     */
    PERMISSIONS_WRITE(Permissions.PERMISSIONS_WRITE);

    private final String value;

    Permission(String value) {
        this.value = value;
    }

    /**
     * Get the string value of this permission.
     *
     * @return the permission string
     */
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Convert a set of Aussie permissions to Quarkus Security roles.
     *
     * @param permissions the set of permissions (as strings)
     * @return the corresponding set of roles
     */
    public static Set<String> toRoles(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }

        Set<String> roles = new HashSet<>();

        for (String permission : permissions) {
            if (Permissions.ALL.equals(permission)) {
                // Wildcard grants all roles
                roles.add(Permissions.ADMIN);
                roles.add(Permissions.SERVICE_CONFIG_READ);
                roles.add(Permissions.SERVICE_CONFIG_CREATE);
                roles.add(Permissions.SERVICE_CONFIG_UPDATE);
                roles.add(Permissions.SERVICE_CONFIG_DELETE);
                roles.add(Permissions.SERVICE_PERMISSIONS_READ);
                roles.add(Permissions.SERVICE_PERMISSIONS_WRITE);
                roles.add(Permissions.APIKEYS_READ);
                roles.add(Permissions.APIKEYS_WRITE);
                roles.add(Permissions.AUTH_ROLES_CREATE);
                roles.add(Permissions.AUTH_ROLES_READ);
                roles.add(Permissions.AUTH_ROLES_UPDATE);
                roles.add(Permissions.AUTH_ROLES_DELETE);
            } else {
                roles.add(permission);
            }
        }

        return roles;
    }

    /**
     * Convert a single Aussie permission to a Quarkus Security role.
     *
     * @param permission the permission string
     * @return the corresponding role
     */
    public static String toRole(String permission) {
        if (permission == null) {
            return null;
        }
        if (Permissions.ALL.equals(permission)) {
            return Permissions.ADMIN;
        }
        return permission;
    }
}
