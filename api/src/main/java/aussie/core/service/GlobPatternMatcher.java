package aussie.core.service;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Matches paths against glob patterns using Java's built-in PathMatcher.
 * Caches compiled patterns for performance.
 */
@ApplicationScoped
public class GlobPatternMatcher {

    private final Map<String, PathMatcher> matcherCache = new ConcurrentHashMap<>();

    /**
     * Tests if a path matches a glob pattern.
     *
     * @param glob the glob pattern (e.g., "/api/users/**", "/api/health")
     * @param path the path to test
     * @return true if the path matches the pattern
     */
    public boolean matches(String glob, String path) {
        var normalizedPath = normalizePath(path);
        var matcher = matcherCache.computeIfAbsent(glob, this::createMatcher);
        return matcher.matches(Path.of(normalizedPath));
    }

    private PathMatcher createMatcher(String glob) {
        return FileSystems.getDefault().getPathMatcher("glob:" + glob);
    }

    /**
     * Normalizes a path by removing trailing slashes and collapsing multiple slashes.
     */
    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // Collapse multiple slashes
        var normalized = path.replaceAll("/+", "/");

        // Remove trailing slash (but keep root slash)
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }
}
