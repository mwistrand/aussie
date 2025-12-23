package aussie.core.model.routing;

import java.util.Optional;
import java.util.Set;

import aussie.core.model.ratelimit.EndpointRateLimitConfig;

/**
 * Configuration for a specific endpoint within a service.
 *
 * @param path            the endpoint path pattern (may include {param} placeholders)
 * @param methods         HTTP methods this endpoint accepts
 * @param visibility      whether this endpoint is PUBLIC or PRIVATE
 * @param pathRewrite     optional path transformation for the upstream request
 * @param authRequired    whether authentication is required for this endpoint
 * @param type            HTTP or WEBSOCKET
 * @param rateLimitConfig optional endpoint-specific rate limiting
 * @param audience        optional audience claim for tokens issued to this endpoint
 */
public record EndpointConfig(
        String path,
        Set<String> methods,
        EndpointVisibility visibility,
        Optional<String> pathRewrite,
        boolean authRequired,
        EndpointType type,
        Optional<EndpointRateLimitConfig> rateLimitConfig,
        Optional<String> audience) {

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
        if (audience == null) {
            audience = Optional.empty();
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
     * Convenience constructor without audience (defaults to empty).
     */
    public EndpointConfig(
            String path,
            Set<String> methods,
            EndpointVisibility visibility,
            Optional<String> pathRewrite,
            boolean authRequired,
            EndpointType type,
            Optional<EndpointRateLimitConfig> rateLimitConfig) {
        this(path, methods, visibility, pathRewrite, authRequired, type, rateLimitConfig, Optional.empty());
    }

    /**
     * Convenience constructor without rate limit config and audience (defaults to HTTP).
     */
    public EndpointConfig(
            String path,
            Set<String> methods,
            EndpointVisibility visibility,
            Optional<String> pathRewrite,
            boolean authRequired,
            EndpointType type) {
        this(path, methods, visibility, pathRewrite, authRequired, type, Optional.empty(), Optional.empty());
    }

    /**
     * Convenience constructor without type, rate limit config, and audience (defaults to HTTP).
     */
    public EndpointConfig(
            String path,
            Set<String> methods,
            EndpointVisibility visibility,
            Optional<String> pathRewrite,
            boolean authRequired) {
        this(
                path,
                methods,
                visibility,
                pathRewrite,
                authRequired,
                EndpointType.HTTP,
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Convenience constructor without authRequired, type, rate limit config, and audience.
     */
    public EndpointConfig(
            String path, Set<String> methods, EndpointVisibility visibility, Optional<String> pathRewrite) {
        this(path, methods, visibility, pathRewrite, false, EndpointType.HTTP, Optional.empty(), Optional.empty());
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
                Optional.empty(),
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
                Optional.empty(),
                Optional.empty());
    }

    public boolean isWebSocket() {
        return type == EndpointType.WEBSOCKET;
    }
}
