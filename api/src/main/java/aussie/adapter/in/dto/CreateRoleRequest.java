package aussie.adapter.in.dto;

import java.util.Set;

/**
 * DTO for role creation requests.
 *
 * @param id          unique identifier for the role (required, e.g., "platform-team")
 * @param displayName human-readable name (optional, defaults to id)
 * @param description optional description of the role's purpose
 * @param permissions set of permissions to grant to members of this role
 */
public record CreateRoleRequest(String id, String displayName, String description, Set<String> permissions) {}
