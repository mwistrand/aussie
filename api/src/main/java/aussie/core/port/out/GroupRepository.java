package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Group;
import aussie.core.model.auth.GroupMapping;

/**
 * Port interface for persistent storage of groups.
 *
 * <p>This is the storage abstraction for group management in RBAC.
 * Groups map to sets of permissions and are used to expand token
 * group claims into effective permissions at validation time.
 */
public interface GroupRepository {

    /**
     * Save or update a group.
     *
     * @param group the group to persist
     * @return Uni completing when save is durable
     */
    Uni<Void> save(Group group);

    /**
     * Find a group by its unique identifier.
     *
     * @param groupId the group identifier
     * @return Uni with Optional containing the group if found
     */
    Uni<Optional<Group>> findById(String groupId);

    /**
     * Delete a group.
     *
     * @param groupId the group identifier to delete
     * @return Uni with true if deleted, false if not found
     */
    Uni<Boolean> delete(String groupId);

    /**
     * Retrieve all groups.
     *
     * @return Uni with list of all groups
     */
    Uni<List<Group>> findAll();

    /**
     * Check if a group exists.
     *
     * @param groupId the group identifier
     * @return Uni with true if exists
     */
    Uni<Boolean> exists(String groupId);

    /**
     * Get the complete group-to-permission mapping.
     *
     * <p>This is used to build the cached mapping for token validation.
     *
     * @return Uni with the complete GroupMapping
     */
    Uni<GroupMapping> getGroupMapping();
}
