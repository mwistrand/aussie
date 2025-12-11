package aussie.core.model;

import java.util.Map;
import java.util.Set;

/**
 * Service-level permission policy that maps operations to allowed permissions.
 *
 * <p>Each service can define which permissions (opaque strings assigned to API keys)
 * are allowed to perform specific operations. Aussie treats permissions as opaque strings
 * and only performs set intersection to determine authorization.
 *
 * <p>Example policy:
 * <pre>{@code
 * {
 *   "permissions": {
 *     "service.config.read": { "anyOfPermissions": ["demo-service.readonly", "demo-service.admin"] },
 *     "service.config.update": { "anyOfPermissions": ["demo-service.admin"] }
 *   }
 * }
 * }</pre>
 *
 * @param permissions map of operation names to their permission rules
 */
public record ServicePermissionPolicy(Map<String, OperationPermission> permissions) {

    public ServicePermissionPolicy {
        if (permissions == null) {
            permissions = Map.of();
        }
    }

    /**
     * Creates an empty policy (no permissions defined).
     */
    public static ServicePermissionPolicy empty() {
        return new ServicePermissionPolicy(Map.of());
    }

    /**
     * Checks if a principal with the given permissions is allowed to perform the operation.
     *
     * @param operation the operation to check (e.g., "service.config.read")
     * @param permissions the permissions from the API key
     * @return true if any permission matches, false otherwise
     */
    public boolean isAllowed(String operation, Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }

        var permission = this.permissions.get(operation);
        if (permission == null) {
            // No permission defined for this operation - deny by default
            return false;
        }

        return permission.isAllowed(permissions);
    }

    /**
     * Returns true if this policy has any permissions defined.
     */
    public boolean hasPermissions() {
        return !permissions.isEmpty();
    }
}
