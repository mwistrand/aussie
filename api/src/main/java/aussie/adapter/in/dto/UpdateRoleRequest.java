package aussie.adapter.in.dto;

import java.util.Set;

/**
 * DTO for role update requests.
 *
 * <p>All fields are optional. Only non-null fields are updated.
 *
 * @param displayName new display name (null to keep current)
 * @param description new description (null to keep current)
 * @param permissions new set of permissions (null to keep current)
 * @param addPermissions permissions to add to existing (null to skip)
 * @param removePermissions permissions to remove from existing (null to skip)
 */
public record UpdateRoleRequest(
        String displayName,
        String description,
        Set<String> permissions,
        Set<String> addPermissions,
        Set<String> removePermissions) {}
