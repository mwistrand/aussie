package aussie.core.port.in;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Group;
import aussie.core.model.auth.GroupMapping;

/**
 * Port for managing RBAC groups.
 *
 * <p>Groups map to sets of permissions and are used to expand token
 * group claims into effective permissions at validation time.
 */
public interface GroupManagement {

    /**
     * Create a new group.
     *
     * @param id          unique identifier (e.g., "platform-team")
     * @param displayName human-readable name
     * @param description optional description
     * @param permissions set of permissions to grant
     * @return Uni with the created group
     * @throws IllegalArgumentException if a group with this ID already exists
     */
    Uni<Group> create(String id, String displayName, String description, Set<String> permissions);

    /**
     * Update an existing group's permissions.
     *
     * @param id          the group ID to update
     * @param permissions new set of permissions
     * @return Uni with the updated group, or empty if not found
     */
    Uni<Optional<Group>> updatePermissions(String id, Set<String> permissions);

    /**
     * Update an existing group's details.
     *
     * @param id          the group ID to update
     * @param displayName new display name (null to keep current)
     * @param description new description (null to keep current)
     * @param permissions new permissions (null to keep current)
     * @return Uni with the updated group, or empty if not found
     */
    Uni<Optional<Group>> update(String id, String displayName, String description, Set<String> permissions);

    /**
     * Get a group by ID.
     *
     * @param id the group ID
     * @return Uni with the group if found, empty otherwise
     */
    Uni<Optional<Group>> get(String id);

    /**
     * List all groups.
     *
     * @return Uni with list of all groups
     */
    Uni<List<Group>> list();

    /**
     * Delete a group.
     *
     * @param id the group ID to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String id);

    /**
     * Get the current group-to-permission mapping.
     *
     * <p>This is used by the gateway to expand group claims in tokens.
     *
     * @return Uni with the group mapping
     */
    Uni<GroupMapping> getGroupMapping();

    /**
     * Expand a set of groups to their effective permissions.
     *
     * @param groups set of group IDs
     * @return Uni with set of all permissions granted by the groups
     */
    Uni<Set<String>> expandGroups(Set<String> groups);
}
