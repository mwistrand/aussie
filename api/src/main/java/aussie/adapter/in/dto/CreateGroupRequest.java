package aussie.adapter.in.dto;

import java.util.Set;

/**
 * DTO for group creation requests.
 *
 * @param id          unique identifier for the group (required, e.g., "platform-team")
 * @param displayName human-readable name (optional, defaults to id)
 * @param description optional description of the group's purpose
 * @param permissions set of permissions to grant to members of this group
 */
public record CreateGroupRequest(String id, String displayName, String description, Set<String> permissions) {}
