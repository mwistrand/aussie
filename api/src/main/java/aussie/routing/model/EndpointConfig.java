package aussie.routing.model;

import java.util.Optional;
import java.util.Set;

public record EndpointConfig(
    String path,
    Set<String> methods,
    EndpointVisibility visibility,
    Optional<String> pathRewrite
) {
    public EndpointConfig {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path cannot be null or blank");
        }
        if (methods == null || methods.isEmpty()) {
            throw new IllegalArgumentException("Methods cannot be null or empty");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Visibility cannot be null");
        }
        if (pathRewrite == null) {
            pathRewrite = Optional.empty();
        }
    }

    public static EndpointConfig publicEndpoint(String path, Set<String> methods) {
        return new EndpointConfig(path, methods, EndpointVisibility.PUBLIC, Optional.empty());
    }

    public static EndpointConfig privateEndpoint(String path, Set<String> methods) {
        return new EndpointConfig(path, methods, EndpointVisibility.PRIVATE, Optional.empty());
    }
}
