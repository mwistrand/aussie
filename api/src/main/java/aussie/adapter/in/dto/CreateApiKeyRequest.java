package aussie.adapter.in.dto;

import java.util.Set;

/**
 * DTO for API key creation requests.
 *
 * @param name        display name for the key (required)
 * @param description optional description of the key's purpose
 * @param permissions set of permissions to grant (e.g., "admin:read", "admin:write")
 * @param ttlDays     time-to-live in days (null = never expires)
 */
public record CreateApiKeyRequest(String name, String description, Set<String> permissions, Integer ttlDays) {}
