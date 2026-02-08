package aussie.adapter.in.dto;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for role creation requests.
 *
 * @param id          unique identifier for the role (required, e.g., "platform-team")
 * @param displayName human-readable name (optional, defaults to id)
 * @param description optional description of the role's purpose
 * @param permissions set of permissions to grant to members of this role
 */
public record CreateRoleRequest(
        @NotBlank(message = "id is required") @Size(max = 64, message = "id must be 64 characters or less") String id,
        @Size(max = 255, message = "displayName must be 255 characters or less") String displayName,
        @Size(max = 1000, message = "description must be 1000 characters or less") String description,
        Set<String> permissions) {}
