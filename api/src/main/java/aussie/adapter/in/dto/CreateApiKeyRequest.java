package aussie.adapter.in.dto;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for API key creation requests.
 *
 * @param name        display name for the key (required)
 * @param description optional description of the key's purpose
 * @param permissions set of permissions to grant (e.g., "service.config:read", "demo-service.admin")
 * @param ttlDays     time-to-live in days (null = never expires)
 */
public record CreateApiKeyRequest(
        @NotBlank(message = "name is required") @Size(max = 255, message = "name must be 255 characters or less")
                String name,
        @Size(max = 1000, message = "description must be 1000 characters or less") String description,
        Set<String> permissions,
        @Min(value = 1, message = "ttlDays must be at least 1") Integer ttlDays) {}
