package aussie.core.model;

import java.util.Collections;
import java.util.Set;

/**
 * Permission rules for a single operation.
 *
 * <p>Currently supports "any of" logic: the operation is allowed if the principal
 * has any one of the listed permissions.
 *
 * @param anyOfPermissions set of permissions, any one of which grants access
 */
public record OperationPermission(Set<String> anyOfPermissions) {

    public OperationPermission {
        if (anyOfPermissions == null) {
            anyOfPermissions = Set.of();
        }
    }

    /**
     * Checks if any of the provided permissions matches the allowed permissions.
     *
     * @param permissions the permissions to check
     * @return true if intersection is non-empty
     */
    public boolean isAllowed(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty() || anyOfPermissions.isEmpty()) {
            return false;
        }
        // Check if there's any intersection
        return !Collections.disjoint(permissions, anyOfPermissions);
    }
}
