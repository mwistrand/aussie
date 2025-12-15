package aussie.core.model.auth;

import java.util.Set;

import aussie.core.model.routing.EndpointVisibility;

/**
 * Defines a visibility rule for path patterns.
 * Rules are matched using glob patterns and can optionally restrict to specific HTTP methods.
 */
public record VisibilityRule(String pattern, Set<String> methods, EndpointVisibility visibility) {
    public VisibilityRule {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Pattern cannot be null or blank");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Visibility cannot be null");
        }
        if (methods == null) {
            methods = Set.of();
        }
    }

    /**
     * Creates a PUBLIC visibility rule for the given pattern.
     */
    public static VisibilityRule publicRule(String pattern) {
        return new VisibilityRule(pattern, Set.of(), EndpointVisibility.PUBLIC);
    }

    /**
     * Creates a PUBLIC visibility rule for the given pattern and methods.
     */
    public static VisibilityRule publicRule(String pattern, Set<String> methods) {
        return new VisibilityRule(pattern, methods, EndpointVisibility.PUBLIC);
    }

    /**
     * Creates a PRIVATE visibility rule for the given pattern.
     */
    public static VisibilityRule privateRule(String pattern) {
        return new VisibilityRule(pattern, Set.of(), EndpointVisibility.PRIVATE);
    }

    /**
     * Creates a PRIVATE visibility rule for the given pattern and methods.
     */
    public static VisibilityRule privateRule(String pattern, Set<String> methods) {
        return new VisibilityRule(pattern, methods, EndpointVisibility.PRIVATE);
    }

    /**
     * Checks if this rule applies to all HTTP methods.
     */
    public boolean appliesToAllMethods() {
        return methods.isEmpty();
    }

    /**
     * Checks if this rule applies to the given HTTP method.
     */
    public boolean appliesToMethod(String method) {
        if (appliesToAllMethods()) {
            return true;
        }
        return methods.contains(method.toUpperCase());
    }
}
