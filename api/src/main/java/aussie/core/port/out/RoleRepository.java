package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Role;
import aussie.core.model.auth.RoleMapping;

/**
 * Port interface for persistent storage of roles.
 *
 * <p>This is the storage abstraction for role management in RBAC.
 * Roles map to sets of permissions and are used to expand token
 * role claims into effective permissions at validation time.
 */
public interface RoleRepository {

    /**
     * Save or update a role.
     *
     * @param role the role to persist
     * @return Uni completing when save is durable
     */
    Uni<Void> save(Role role);

    /**
     * Find a role by its unique identifier.
     *
     * @param roleId the role identifier
     * @return Uni with Optional containing the role if found
     */
    Uni<Optional<Role>> findById(String roleId);

    /**
     * Delete a role.
     *
     * @param roleId the role identifier to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String roleId);

    /**
     * Retrieve all roles.
     *
     * @return Uni with list of all roles
     */
    Uni<List<Role>> findAll();

    /**
     * Check if a role exists.
     *
     * @param roleId the role identifier
     * @return Uni with true if exists
     */
    Uni<Boolean> exists(String roleId);

    /**
     * Get the complete role-to-permission mapping.
     *
     * <p>This is used to build the cached mapping for token validation.
     *
     * @return Uni with the complete RoleMapping
     */
    Uni<RoleMapping> getRoleMapping();
}
