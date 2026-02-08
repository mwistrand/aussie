package aussie.adapter.in.dto;

import java.util.Set;

import jakarta.validation.constraints.Size;

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
        @Size(max = 255, message = "displayName must be 255 characters or less") String displayName,
        @Size(max = 1000, message = "description must be 1000 characters or less") String description,
        Set<String> permissions,
        Set<String> addPermissions,
        Set<String> removePermissions) {}
