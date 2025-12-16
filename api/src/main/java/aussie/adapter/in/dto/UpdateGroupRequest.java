package aussie.adapter.in.dto;

import java.util.Set;

/**
 * DTO for group update requests.
 *
 * <p>All fields are optional. Only non-null fields are updated.
 *
 * @param displayName new display name (null to keep current)
 * @param description new description (null to keep current)
 * @param permissions new set of permissions (null to keep current)
 */
public record UpdateGroupRequest(String displayName, String description, Set<String> permissions) {}
