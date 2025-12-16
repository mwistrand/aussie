package aussie.core.model.auth;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a mapping from group IDs to their associated permissions.
 *
 * <p>This is used at token validation time to expand group claims into
 * effective permissions. The mapping is typically cached and refreshed
 * when groups are modified.
 *
 * @param groupToPermissions map from group ID to set of permissions
 */
public record GroupMapping(Map<String, Set<String>> groupToPermissions) {

    public GroupMapping {
        if (groupToPermissions == null) {
            groupToPermissions = Map.of();
        } else {
            groupToPermissions = Map.copyOf(groupToPermissions);
        }
    }

    /**
     * Create an empty group mapping.
     *
     * @return an empty GroupMapping
     */
    public static GroupMapping empty() {
        return new GroupMapping(Map.of());
    }

    /**
     * Expand a set of groups to their associated permissions.
     *
     * <p>Unknown groups are silently ignored (return empty permissions).
     *
     * @param groups set of group IDs to expand
     * @return set of all permissions granted by the groups
     */
    public Set<String> expandGroups(Set<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return Set.of();
        }

        return groups.stream()
                .flatMap(group -> groupToPermissions.getOrDefault(group, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Get effective permissions by combining group expansion with direct permissions.
     *
     * @param groups            set of group IDs to expand
     * @param directPermissions set of permissions granted directly (not via groups)
     * @return combined set of all effective permissions
     */
    public Set<String> getEffectivePermissions(Set<String> groups, Set<String> directPermissions) {
        final var expandedPermissions = expandGroups(groups);
        final var direct = directPermissions != null ? directPermissions : Set.<String>of();

        return Stream.concat(expandedPermissions.stream(), direct.stream()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Check if a group exists in this mapping.
     *
     * @param groupId the group ID to check
     * @return true if the group exists
     */
    public boolean hasGroup(String groupId) {
        return groupToPermissions.containsKey(groupId);
    }

    /**
     * Get the number of groups in this mapping.
     *
     * @return the number of groups
     */
    public int size() {
        return groupToPermissions.size();
    }
}
