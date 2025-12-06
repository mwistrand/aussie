package aussie.core.model;

import java.util.Map;

/**
 * Represents an authenticated identity (user, service, or system).
 *
 * @param id         unique identifier for this principal
 * @param name       human-readable display name
 * @param type       the type of principal ("user", "service", "system")
 * @param attributes additional metadata about this principal
 */
public record Principal(String id, String name, String type, Map<String, String> attributes) {

    public Principal {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Principal ID cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            name = id;
        }
        if (type == null || type.isBlank()) {
            type = "unknown";
        }
        if (attributes == null) {
            attributes = Map.of();
        }
    }

    public static Principal system(String name) {
        return new Principal("system", name, "system", Map.of());
    }

    public static Principal service(String id, String name) {
        return new Principal(id, name, "service", Map.of());
    }

    public static Principal user(String id, String name) {
        return new Principal(id, name, "user", Map.of());
    }
}
