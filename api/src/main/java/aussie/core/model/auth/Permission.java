package aussie.core.model.auth;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Permission enum for Aussie API authentication.
 *
 * <p>Each enum constant encapsulates a permission string value. The static constants
 * in this class (e.g., {@link #ALL_VALUE}, {@link #ADMIN_VALUE}) can be used in
 * annotations that require compile-time string constants.
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
 */
public enum Permission {

    // ========================================================================
    // Enum constants (must come first in Java enums)
    // ========================================================================

    /** Wildcard permission that grants access to everything. */
    ALL("*"),

    /** Default admin claim used for service-level authorization. */
    ADMIN_CLAIM("aussie:admin"),

    /** Role for full admin access (granted by wildcard permission). */
    ADMIN("admin"),

    /** Role for reading service configurations. */
    SERVICE_CONFIG_READ("service.config.read"),

    /** Role for creating service configurations. */
    SERVICE_CONFIG_CREATE("service.config.create"),

    /** Role for updating service configurations. */
    SERVICE_CONFIG_UPDATE("service.config.update"),

    /** Role for deleting service configurations. */
    SERVICE_CONFIG_DELETE("service.config.delete"),

    /** Role for reading service permission policies. */
    SERVICE_PERMISSIONS_READ("service.permissions.read"),

    /** Role for writing service permission policies. */
    SERVICE_PERMISSIONS_WRITE("service.permissions.write"),

    /** Role for reading API keys. */
    APIKEYS_READ("apikeys.read"),

    /** Role for writing API keys. */
    APIKEYS_WRITE("apikeys.write"),

    /** Permission for creating roles. */
    AUTH_ROLES_CREATE("auth.roles.create"),

    /** Permission for reading roles. */
    AUTH_ROLES_READ("auth.roles.read"),

    /** Permission for updating roles. */
    AUTH_ROLES_UPDATE("auth.roles.update"),

    /** Permission for deleting roles. */
    AUTH_ROLES_DELETE("auth.roles.delete"),

    /** Permission for reading token revocation status. */
    TOKENS_READ("tokens.read"),

    /** Permission for revoking tokens. */
    TOKENS_REVOKE("tokens.revoke"),

    /** Permission for reading lockout status. */
    LOCKOUTS_READ("lockouts.read"),

    /** Permission for clearing lockouts. */
    LOCKOUTS_WRITE("lockouts.write"),

    /** Permission for reading signing keys. */
    KEYS_READ("keys.read"),

    /** Permission for writing/managing signing keys. */
    KEYS_WRITE("keys.write"),

    /** Permission for rotating signing keys. */
    KEYS_ROTATE("keys.rotate"),

    /** Service configuration create operation (alias for SERVICE_CONFIG_CREATE). */
    CONFIG_CREATE("service.config.create"),

    /** Service configuration read operation (alias for SERVICE_CONFIG_READ). */
    CONFIG_READ("service.config.read"),

    /** Service configuration update operation (alias for SERVICE_CONFIG_UPDATE). */
    CONFIG_UPDATE("service.config.update"),

    /** Service configuration delete operation (alias for SERVICE_CONFIG_DELETE). */
    CONFIG_DELETE("service.config.delete"),

    /** Permission policy read operation (alias for SERVICE_PERMISSIONS_READ). */
    PERMISSIONS_READ("service.permissions.read"),

    /** Permission policy update operation (alias for SERVICE_PERMISSIONS_WRITE). */
    PERMISSIONS_WRITE("service.permissions.write");

    // ========================================================================
    // String constants for use in annotations (compile-time constants)
    // These MUST be declared after enum constants in Java
    // ========================================================================

    /** Wildcard permission value. */
    public static final String ALL_VALUE = "*";
    /** Admin claim value for service-level authorization. */
    public static final String ADMIN_CLAIM_VALUE = "aussie:admin";
    /** Admin role value. */
    public static final String ADMIN_VALUE = "admin";
    /** Service config read permission value. */
    public static final String SERVICE_CONFIG_READ_VALUE = "service.config.read";
    /** Service config create permission value. */
    public static final String SERVICE_CONFIG_CREATE_VALUE = "service.config.create";
    /** Service config update permission value. */
    public static final String SERVICE_CONFIG_UPDATE_VALUE = "service.config.update";
    /** Service config delete permission value. */
    public static final String SERVICE_CONFIG_DELETE_VALUE = "service.config.delete";
    /** Service permissions read permission value. */
    public static final String SERVICE_PERMISSIONS_READ_VALUE = "service.permissions.read";
    /** Service permissions write permission value. */
    public static final String SERVICE_PERMISSIONS_WRITE_VALUE = "service.permissions.write";
    /** API keys read permission value. */
    public static final String APIKEYS_READ_VALUE = "apikeys.read";
    /** API keys write permission value. */
    public static final String APIKEYS_WRITE_VALUE = "apikeys.write";
    /** Auth roles create permission value. */
    public static final String AUTH_ROLES_CREATE_VALUE = "auth.roles.create";
    /** Auth roles read permission value. */
    public static final String AUTH_ROLES_READ_VALUE = "auth.roles.read";
    /** Auth roles update permission value. */
    public static final String AUTH_ROLES_UPDATE_VALUE = "auth.roles.update";
    /** Auth roles delete permission value. */
    public static final String AUTH_ROLES_DELETE_VALUE = "auth.roles.delete";
    /** Tokens read permission value. */
    public static final String TOKENS_READ_VALUE = "tokens.read";
    /** Tokens revoke permission value. */
    public static final String TOKENS_REVOKE_VALUE = "tokens.revoke";
    /** Lockouts read permission value. */
    public static final String LOCKOUTS_READ_VALUE = "lockouts.read";
    /** Lockouts write permission value. */
    public static final String LOCKOUTS_WRITE_VALUE = "lockouts.write";
    /** Keys read permission value. */
    public static final String KEYS_READ_VALUE = "keys.read";
    /** Keys write permission value. */
    public static final String KEYS_WRITE_VALUE = "keys.write";
    /** Keys rotate permission value. */
    public static final String KEYS_ROTATE_VALUE = "keys.rotate";

    // ========================================================================
    // Static set of all permission values (populated at class initialization)
    // ========================================================================

    /** Unmodifiable set of all permission string values. */
    private static final Set<String> ALL_PERMISSION_VALUES;

    static {
        Set<String> values = new TreeSet<>();
        for (Permission p : Permission.values()) {
            values.add(p.value);
        }
        ALL_PERMISSION_VALUES = Collections.unmodifiableSet(values);
    }

    // ========================================================================
    // Instance field and constructor
    // ========================================================================

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
            if (ALL_VALUE.equals(permission)) {
                // Wildcard grants all roles
                roles.addAll(ALL_PERMISSION_VALUES);
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
        if (ALL_VALUE.equals(permission)) {
            return ADMIN_VALUE;
        }
        return permission;
    }
}
