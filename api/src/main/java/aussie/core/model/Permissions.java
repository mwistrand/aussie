package aussie.core.model;

/**
 * Permission constants for use with @RolesAllowed annotations.
 * Permissions are stored in the AuthenticationContext and checked by Quarkus Security.
 */
public final class Permissions {

    /**
     * Allows read access to admin endpoints (GET on /admin/*).
     */
    public static final String ADMIN_READ = "admin:read";

    /**
     * Allows write access to admin endpoints (POST, PUT, DELETE on /admin/*).
     */
    public static final String ADMIN_WRITE = "admin:write";

    /**
     * Wildcard permission that grants access to everything.
     * Used by the dangerous-noop provider in development mode.
     */
    public static final String ALL = "*";

    private Permissions() {
        // Prevent instantiation
    }
}
