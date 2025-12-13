package aussie.core.model;

import java.util.Optional;
import java.util.Set;

public record EndpointConfig(
        String path,
        Set<String> methods,
        EndpointVisibility visibility,
        Optional<String> pathRewrite,
        boolean authRequired,
        EndpointType type,
        Optional<EndpointRateLimitConfig> rateLimitConfig) {

    public EndpointConfig {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path cannot be null or blank");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Visibility cannot be null");
        }
        if (pathRewrite == null) {
            pathRewrite = Optional.empty();
        }
        if (type == null) {
            type = EndpointType.HTTP;
        }
        if (rateLimitConfig == null) {
            rateLimitConfig = Optional.empty();
        }
        // For WebSocket endpoints, default methods to GET if not specified
        if (type == EndpointType.WEBSOCKET && (methods == null || methods.isEmpty())) {
            methods = Set.of("GET");
        }
        // For HTTP endpoints, methods are required
        if (type == EndpointType.HTTP && (methods == null || methods.isEmpty())) {
            throw new IllegalArgumentException("Methods cannot be null or empty for HTTP endpoints");
        }
    }

    /**
     * Convenience constructor without rate limit config and type (defaults to HTTP).
     */
    public EndpointConfig(
            String path,
            Set<String> methods,
            EndpointVisibility visibility,
            Optional<String> pathRewrite,
            boolean authRequired,
            EndpointType type) {
        this(path, methods, visibility, pathRewrite, authRequired, type, Optional.empty());
    }

    /**
     * Convenience constructor without type (defaults to HTTP).
     */
    public EndpointConfig(
            String path,
            Set<String> methods,
            EndpointVisibility visibility,
            Optional<String> pathRewrite,
            boolean authRequired) {
        this(path, methods, visibility, pathRewrite, authRequired, EndpointType.HTTP, Optional.empty());
    }

    /**
     * Convenience constructor without authRequired and type (defaults to false and
     * HTTP).
     */
    public EndpointConfig(
            String path, Set<String> methods, EndpointVisibility visibility, Optional<String> pathRewrite) {
        this(path, methods, visibility, pathRewrite, false, EndpointType.HTTP, Optional.empty());
    }

    public static EndpointConfig publicEndpoint(String path, Set<String> methods) {
        return new EndpointConfig(path, methods, EndpointVisibility.PUBLIC, Optional.empty());
    }

    public static EndpointConfig privateEndpoint(String path, Set<String> methods) {
        return new EndpointConfig(path, methods, EndpointVisibility.PRIVATE, Optional.empty());
    }

    public static EndpointConfig publicWebSocket(String path, boolean authRequired) {
        return new EndpointConfig(
                path,
                Set.of("GET"),
                EndpointVisibility.PUBLIC,
                Optional.empty(),
                authRequired,
                EndpointType.WEBSOCKET,
                Optional.empty());
    }

    public static EndpointConfig privateWebSocket(String path, boolean authRequired) {
        return new EndpointConfig(
                path,
                Set.of("GET"),
                EndpointVisibility.PRIVATE,
                Optional.empty(),
                authRequired,
                EndpointType.WEBSOCKET,
                Optional.empty());
    }

    public boolean isWebSocket() {
        return type == EndpointType.WEBSOCKET;
    }
}
