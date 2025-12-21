package aussie.core.model.auth;

/**
 * String constants for permissions, used in {@code @RolesAllowed} annotations.
 *
 * <p>This class provides compile-time string constants required by Java annotations.
 * For programmatic permission handling, use the {@link Permission} enum instead.
 *
 * <p>Permissions follow a hierarchical format and represent operations that can be
 * performed on services and resources.
 *
 * @deprecated Use {@link Permission} instead. This class will be removed in a future version.
 *             For annotation constants, use {@code Permission.*_VALUE} constants (e.g.,
 *             {@link Permission#ADMIN_VALUE}, {@link Permission#TOKENS_READ_VALUE}).
 * @see Permission
 */
@Deprecated(forRemoval = true)
public final class Permissions {

    private Permissions() {
        // Prevent instantiation
    }

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
    // Role management permissions (for @RolesAllowed)
    // ========================================================================

    /**
     * Permission for creating roles.
     */
    public static final String AUTH_ROLES_CREATE = "auth.roles.create";

    /**
     * Permission for reading roles.
     */
    public static final String AUTH_ROLES_READ = "auth.roles.read";

    /**
     * Permission for updating roles.
     */
    public static final String AUTH_ROLES_UPDATE = "auth.roles.update";

    /**
     * Permission for deleting roles.
     */
    public static final String AUTH_ROLES_DELETE = "auth.roles.delete";

    // ========================================================================
    // Token revocation permissions
    // ========================================================================

    /**
     * Permission for reading token revocation status.
     */
    public static final String TOKENS_READ = "tokens.read";

    /**
     * Permission for revoking tokens.
     */
    public static final String TOKENS_REVOKE = "tokens.revoke";

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
}
