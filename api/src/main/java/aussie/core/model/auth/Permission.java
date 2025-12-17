package aussie.core.model.auth;

import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Permission constants and role mapping for Aussie API authentication.
 *
 * <p>
 * Permissions follow a hierarchical format and represent operations that can be
 * performed on services and resources. These are the canonical operation names
 * used in permission policies. The vocabulary is stable and defined by Aussie,
 * while the claims that map to these operations are defined by the organization.
 *
 * <p>
 * The wildcard permission {@code *} grants all permissions.
 *
 * <p>
 * Roles are derived from permissions and used by {@code @RolesAllowed}
 * annotations in JAX-RS resources.
 *
 * <h2>Important Distinction: ADMIN_CLAIM vs ADMIN</h2>
 * <p>
 * These serve different purposes and should NOT be consolidated:
 * <ul>
 * <li>{@link #ADMIN_CLAIM} ({@code "aussie:admin"}) - A token claim value used
 * by {@code DefaultPermissionPolicy} for service-level authorization. Controls
 * which backend services can be accessed through the gateway.</li>
 * <li>{@link #ADMIN} ({@code "admin"}) - A Quarkus Security role used with
 * {@code @RolesAllowed} annotations for endpoint-level authorization. Controls
 * access to admin REST endpoints.</li>
 * </ul>
 */
@ApplicationScoped
public class Permission {

    // ========================================================================
    // Wildcard permission
    // ========================================================================

    /**
     * Wildcard permission that grants access to everything.
     * Used by the dangerous-noop provider in development mode.
     */
    public static final String ALL = "*";

    /**
     * Default admin claim used for service-level authorization.
     * This claim grants full access when no explicit permission policy is defined.
     */
    public static final String ADMIN_CLAIM = "aussie:admin";

    // ========================================================================
    // Role constants (used by @RolesAllowed)
    // ========================================================================

    /**
     * Role for full admin access (granted by wildcard permission).
     */
    public static final String ADMIN = "admin";

    /**
     * Role for reading service configurations.
     */
    public static final String SERVICE_CONFIG_READ = "service.config.read";

    /**
     * Role for creating service configurations.
     */
    public static final String SERVICE_CONFIG_CREATE = "service.config.create";

    /**
     * Role for updating service configurations.
     */
    public static final String SERVICE_CONFIG_UPDATE = "service.config.update";

    /**
     * Role for deleting service configurations.
     */
    public static final String SERVICE_CONFIG_DELETE = "service.config.delete";

    /**
     * Role for reading service permission policies.
     */
    public static final String SERVICE_PERMISSIONS_READ = "service.permissions.read";

    /**
     * Role for writing service permission policies.
     */
    public static final String SERVICE_PERMISSIONS_WRITE = "service.permissions.write";

    /**
     * Role for reading API keys.
     */
    public static final String APIKEYS_READ = "apikeys.read";

    /**
     * Role for writing API keys.
     */
    public static final String APIKEYS_WRITE = "apikeys.write";

    // ========================================================================
    // Group management roles (for @RolesAllowed)
    // ========================================================================

    /**
     * Role for creating groups.
     */
    public static final String AUTH_GROUPS_CREATE = "auth.groups.create";

    /**
     * Role for reading groups.
     */
    public static final String AUTH_GROUPS_READ = "auth.groups.read";

    /**
     * Role for updating groups.
     */
    public static final String AUTH_GROUPS_UPDATE = "auth.groups.update";

    /**
     * Role for deleting groups.
     */
    public static final String AUTH_GROUPS_DELETE = "auth.groups.delete";

    // ========================================================================
    // Service operation constants (for permission policies)
    // ========================================================================

    /**
     * Service configuration create operation.
     */
    public static final String CONFIG_CREATE = "service.config.create";

    /**
     * Service configuration read operation.
     */
    public static final String CONFIG_READ = "service.config.read";

    /**
     * Service configuration update operation.
     */
    public static final String CONFIG_UPDATE = "service.config.update";

    /**
     * Service configuration delete operation.
     */
    public static final String CONFIG_DELETE = "service.config.delete";

    /**
     * Permission policy read operation.
     */
    public static final String PERMISSIONS_READ = "service.permissions.read";

    /**
     * Permission policy update operation.
     */
    public static final String PERMISSIONS_WRITE = "service.permissions.write";

    // Future operations (not yet implemented)
    // public static final String METRICS_READ = "service.metrics.read";
    // public static final String LOGS_READ = "service.logs.read";

    /**
     * Convert a set of Aussie permissions to Quarkus Security roles.
     *
     * @param permissions the set of permissions
     * @return the corresponding set of roles
     */
    public Set<String> toRoles(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }

        Set<String> roles = new HashSet<>();

        for (String permission : permissions) {
            if (ALL.equals(permission)) {
                // Wildcard grants all roles
                roles.add(ADMIN);
                roles.add(SERVICE_CONFIG_READ);
                roles.add(SERVICE_CONFIG_CREATE);
                roles.add(SERVICE_CONFIG_UPDATE);
                roles.add(SERVICE_CONFIG_DELETE);
                roles.add(SERVICE_PERMISSIONS_READ);
                roles.add(SERVICE_PERMISSIONS_WRITE);
                roles.add(APIKEYS_READ);
                roles.add(APIKEYS_WRITE);
                roles.add(AUTH_GROUPS_CREATE);
                roles.add(AUTH_GROUPS_READ);
                roles.add(AUTH_GROUPS_UPDATE);
                roles.add(AUTH_GROUPS_DELETE);
            } else {
                // Convert colon to hyphen: service.config:read -> service.config-read
                String role = permission.replace(':', '-');
                roles.add(role);
            }
        }

        return roles;
    }

    /**
     * Convert a single Aussie permission to a Quarkus Security role.
     *
     * @param permission the permission
     * @return the corresponding role
     */
    public String toRole(String permission) {
        if (permission == null) {
            return null;
        }
        if (ALL.equals(permission)) {
            return ADMIN;
        }
        return permission;
    }
}
