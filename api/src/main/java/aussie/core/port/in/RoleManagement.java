package aussie.core.port.in;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Role;
import aussie.core.model.auth.RoleMapping;

/**
 * Port for managing RBAC roles.
 *
 * <p>Roles map to sets of permissions and are used to expand token
 * role claims into effective permissions at validation time.
 */
public interface RoleManagement {

    /**
     * Create a new role.
     *
     * @param id          unique identifier (e.g., "platform-team")
     * @param displayName human-readable name
     * @param description optional description
     * @param permissions set of permissions to grant
     * @return Uni with the created role
     * @throws IllegalArgumentException if a role with this ID already exists
     */
    Uni<Role> create(String id, String displayName, String description, Set<String> permissions);

    /**
     * Update an existing role's permissions.
     *
     * @param id          the role ID to update
     * @param permissions new set of permissions
     * @return Uni with the updated role, or empty if not found
     */
    Uni<Optional<Role>> updatePermissions(String id, Set<String> permissions);

    /**
     * Update an existing role's details.
     *
     * @param id          the role ID to update
     * @param displayName new display name (null to keep current)
     * @param description new description (null to keep current)
     * @param permissions new permissions (null to keep current)
     * @return Uni with the updated role, or empty if not found
     */
    Uni<Optional<Role>> update(String id, String displayName, String description, Set<String> permissions);

    /**
     * Get a role by ID.
     *
     * @param id the role ID
     * @return Uni with the role if found, empty otherwise
     */
    Uni<Optional<Role>> get(String id);

    /**
     * List all roles.
     *
     * @return Uni with list of all roles
     */
    Uni<List<Role>> list();

    /**
     * Delete a role.
     *
     * @param id the role ID to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String id);

    /**
     * Get the current role-to-permission mapping.
     *
     * <p>This is used by the gateway to expand role claims in tokens.
     *
     * @return Uni with the role mapping
     */
    Uni<RoleMapping> getRoleMapping();

    /**
     * Expand a set of roles to their effective permissions.
     *
     * @param roles set of role IDs
     * @return Uni with set of all permissions granted by the roles
     */
    Uni<Set<String>> expandRoles(Set<String> roles);
}
