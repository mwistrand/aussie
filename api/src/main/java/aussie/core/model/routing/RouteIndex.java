package aussie.core.model.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aussie.core.model.service.ServiceRegistration;

/**
 * Precomputed lookup table for a service's endpoints.
 *
 * <p>Endpoints are bucketed by the literal first path segment so a request lookup
 * touches only the candidates whose first segment matches (plus a small list of
 * wildcard-prefixed endpoints). Within candidate lists, registration order is
 * preserved so first-match-wins semantics are unchanged.
 *
 * <p>Path patterns are compiled once at index construction and reused across
 * requests. Path variable names are extracted at build time.
 */
public final class RouteIndex {

    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("\\{([^/]+)\\}");
    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private final Map<String, List<CompiledEndpoint>> byFirstSegment;
    private final List<CompiledEndpoint> wildcardEndpoints;

    public static RouteIndex build(List<EndpointConfig> endpoints) {
        final var byFirstSegment = new HashMap<String, List<CompiledEndpoint>>();
        final var wildcards = new ArrayList<CompiledEndpoint>();

        for (var i = 0; i < endpoints.size(); i++) {
            final var endpoint = endpoints.get(i);
            final var compiled = CompiledEndpoint.of(endpoint, i);
            final var firstSegment = compiled.firstStaticSegment;
            if (firstSegment == null) {
                wildcards.add(compiled);
            } else {
                byFirstSegment
                        .computeIfAbsent(firstSegment, k -> new ArrayList<>())
                        .add(compiled);
            }
        }

        // Make buckets immutable for predictable iteration with no defensive copies on lookup
        final var immutableBuckets = new HashMap<String, List<CompiledEndpoint>>(byFirstSegment.size());
        for (var entry : byFirstSegment.entrySet()) {
            immutableBuckets.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new RouteIndex(immutableBuckets, List.copyOf(wildcards));
    }

    static RouteIndex buildGateway(List<ServiceRegistration> services) {
        final var byFirstSegment = new HashMap<String, List<CompiledEndpoint>>();
        final var wildcards = new ArrayList<CompiledEndpoint>();
        var order = 0;
        for (final var service : services) {
            for (final var endpoint : service.endpoints()) {
                final var compiled = CompiledEndpoint.of(endpoint, order++, service);
                if (compiled.firstStaticSegment == null) {
                    wildcards.add(compiled);
                } else {
                    byFirstSegment
                            .computeIfAbsent(compiled.firstStaticSegment, ignored -> new ArrayList<>())
                            .add(compiled);
                }
            }
        }

        final var immutableBuckets = new HashMap<String, List<CompiledEndpoint>>(byFirstSegment.size());
        byFirstSegment.forEach((segment, routes) -> immutableBuckets.put(segment, List.copyOf(routes)));
        return new RouteIndex(immutableBuckets, List.copyOf(wildcards));
    }

    private RouteIndex(Map<String, List<CompiledEndpoint>> byFirstSegment, List<CompiledEndpoint> wildcardEndpoints) {
        this.byFirstSegment = byFirstSegment;
        this.wildcardEndpoints = wildcardEndpoints;
    }

    /**
     * Find a matching endpoint for the given normalized path and uppercased method.
     *
     * <p>Iterates first-segment-bucket candidates and wildcard candidates merged in
     * registration order so the first-match-wins behavior matches a linear scan.
     *
     * @see #match(ServiceRegistration, String, String) for the nullable hot-path variant
     */
    public Optional<RouteMatch> findMatch(ServiceRegistration service, String normalizedPath, String upperMethod) {
        return Optional.ofNullable(match(service, normalizedPath, upperMethod));
    }

    /**
     * Nullable hot-path variant of {@link #findMatch}: returns {@code null} on miss to
     * avoid the per-request {@code Optional} allocation incurred by {@code findMatch}.
     *
     * @return the matching {@link RouteMatch}, or {@code null} if no endpoint matched
     */
    public RouteMatch match(ServiceRegistration service, String normalizedPath, String upperMethod) {
        final var firstSegment = firstSegmentOf(normalizedPath);
        final var bucket = byFirstSegment.getOrDefault(firstSegment, List.of());
        final var wildcards = wildcardEndpoints;

        if (bucket.isEmpty() && wildcards.isEmpty()) {
            return null;
        }

        var i = 0;
        var j = 0;
        while (i < bucket.size() || j < wildcards.size()) {
            final CompiledEndpoint candidate;
            if (i < bucket.size() && (j >= wildcards.size() || bucket.get(i).order <= wildcards.get(j).order)) {
                candidate = bucket.get(i++);
            } else {
                candidate = wildcards.get(j++);
            }

            final var match = candidate.tryMatch(service, normalizedPath, upperMethod);
            if (match != null) {
                return match;
            }
        }

        return null;
    }

    RouteMatch match(String normalizedPath, String upperMethod) {
        return match(null, normalizedPath, upperMethod);
    }

    /**
     * Return the first path segment (between leading '/' and next '/'), or empty string for "/".
     */
    private static String firstSegmentOf(String normalizedPath) {
        if (normalizedPath.length() <= 1) {
            return "";
        }
        final var nextSlash = normalizedPath.indexOf('/', 1);
        return nextSlash < 0 ? normalizedPath.substring(1) : normalizedPath.substring(1, nextSlash);
    }

    /**
     * Single endpoint with its precompiled pattern, parameter names, and registration order.
     */
    static final class CompiledEndpoint {
        final EndpointConfig endpoint;
        final ServiceRegistration service;
        final Pattern pattern;
        final List<String> paramNames;
        final String firstStaticSegment; // null if first segment is wildcard/parameterized
        final int order;

        private CompiledEndpoint(
                EndpointConfig endpoint,
                ServiceRegistration service,
                Pattern pattern,
                List<String> paramNames,
                String firstStaticSegment,
                int order) {
            this.endpoint = endpoint;
            this.service = service;
            this.pattern = pattern;
            this.paramNames = paramNames;
            this.firstStaticSegment = firstStaticSegment;
            this.order = order;
        }

        static CompiledEndpoint of(EndpointConfig endpoint, int order) {
            return of(endpoint, order, null);
        }

        static CompiledEndpoint of(EndpointConfig endpoint, int order, ServiceRegistration service) {
            final var template = endpoint.path();
            final var pattern = compilePathPattern(template);
            final var paramNames = extractParamNames(template);
            final var firstStaticSegment = staticFirstSegment(template);
            return new CompiledEndpoint(endpoint, service, pattern, paramNames, firstStaticSegment, order);
        }

        RouteMatch tryMatch(ServiceRegistration service, String normalizedPath, String upperMethod) {
            final var methods = endpoint.methods();
            if (!methods.contains(upperMethod) && !methods.contains("*")) {
                return null;
            }

            final var matcher = pattern.matcher(normalizedPath);
            if (!matcher.matches()) {
                return null;
            }

            final var pathVariables = extractPathVariables(matcher);
            final var targetPath = endpoint.pathRewrite()
                    .map(rewrite -> applyPathRewrite(rewrite, pathVariables))
                    .orElse(normalizedPath);

            return new RouteMatch(this.service == null ? service : this.service, endpoint, targetPath, pathVariables);
        }

        private Map<String, String> extractPathVariables(Matcher matcher) {
            if (paramNames.isEmpty()) {
                return Map.of();
            }
            final var variables = new HashMap<String, String>(paramNames.size());
            for (var name : paramNames) {
                try {
                    final var value = matcher.group(name);
                    if (value != null) {
                        variables.put(name, value);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Group not found, skip
                }
            }
            return variables;
        }

        private static String applyPathRewrite(String rewritePattern, Map<String, String> pathVariables) {
            if (pathVariables.isEmpty()) {
                return rewritePattern;
            }
            var result = rewritePattern;
            for (final var entry : pathVariables.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }

        private static Pattern compilePathPattern(String pathTemplate) {
            final var regex = new StringBuilder("^");
            final var names = new HashSet<String>();
            for (var index = 0; index < pathTemplate.length(); ) {
                final var current = pathTemplate.charAt(index);
                if (current == '{') {
                    final var end = pathTemplate.indexOf('}', index + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("Unclosed route parameter in path: " + pathTemplate);
                    }
                    final var name = pathTemplate.substring(index + 1, end);
                    if (!PARAM_NAME.matcher(name).matches() || !names.add(name)) {
                        throw new IllegalArgumentException("Invalid or duplicate route parameter: " + name);
                    }
                    regex.append("(?<").append(name).append(">[^/]+)");
                    index = end + 1;
                } else if (current == '}') {
                    throw new IllegalArgumentException("Unexpected '}' in route path: " + pathTemplate);
                } else if (current == '*') {
                    final var doubleStar = index + 1 < pathTemplate.length() && pathTemplate.charAt(index + 1) == '*';
                    regex.append(doubleStar ? ".*" : "[^/]*");
                    index += doubleStar ? 2 : 1;
                } else {
                    final var special = nextSpecial(pathTemplate, index);
                    regex.append(Pattern.quote(pathTemplate.substring(index, special)));
                    index = special;
                }
            }
            return Pattern.compile(regex.append('$').toString());
        }

        private static int nextSpecial(String path, int start) {
            var index = start;
            while (index < path.length() && "{}*".indexOf(path.charAt(index)) < 0) {
                index++;
            }
            return index;
        }

        private static List<String> extractParamNames(String pathTemplate) {
            final var matcher = PARAM_NAME_PATTERN.matcher(pathTemplate);
            List<String> names = null;
            while (matcher.find()) {
                if (names == null) {
                    names = new ArrayList<>(2);
                }
                names.add(matcher.group(1));
            }
            return names == null ? List.of() : Collections.unmodifiableList(names);
        }

        /**
         * Return the literal first segment of the path template, or null if the
         * first segment contains a parameter or wildcard. Used to bucket endpoints
         * for fast lookup; null routes the endpoint to the wildcard list scanned
         * on every request.
         */
        private static String staticFirstSegment(String pathTemplate) {
            if (pathTemplate.isEmpty() || "/".equals(pathTemplate)) {
                return "";
            }
            final var start = pathTemplate.charAt(0) == '/' ? 1 : 0;
            if (start >= pathTemplate.length()) {
                return "";
            }
            final var nextSlash = pathTemplate.indexOf('/', start);
            final var end = nextSlash < 0 ? pathTemplate.length() : nextSlash;
            final var segment = pathTemplate.substring(start, end);
            if (containsAny(segment, WILDCARD_CHARS)) {
                return null;
            }
            return segment;
        }

        private static final char[] WILDCARD_CHARS = {'{', '}', '*'};

        private static boolean containsAny(String s, char[] chars) {
            for (var i = 0; i < s.length(); i++) {
                final var c = s.charAt(i);
                for (var w : chars) {
                    if (c == w) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    Set<String> bucketKeys() {
        return Collections.unmodifiableSet(byFirstSegment.keySet());
    }

    int wildcardCount() {
        return wildcardEndpoints.size();
    }

    static boolean overlaps(EndpointConfig left, EndpointConfig right) {
        if (!methodsOverlap(left.methods(), right.methods())) {
            return false;
        }
        final var leftFirstSegment = CompiledEndpoint.staticFirstSegment(left.path());
        final var rightFirstSegment = CompiledEndpoint.staticFirstSegment(right.path());
        if (leftFirstSegment != null && rightFirstSegment != null && !leftFirstSegment.equals(rightFirstSegment)) {
            return false;
        }
        return pathsOverlap(tokens(left.path()), tokens(right.path()));
    }

    private static boolean methodsOverlap(Set<String> left, Set<String> right) {
        return left.contains("*") || right.contains("*") || left.stream().anyMatch(right::contains);
    }

    private static List<PathToken> tokens(String path) {
        final var tokens = new ArrayList<PathToken>(path.length());
        for (var index = 0; index < path.length(); ) {
            final var current = path.charAt(index);
            if (current == '{') {
                tokens.add(PathToken.NON_SLASH);
                tokens.add(PathToken.STAR);
                index = path.indexOf('}', index + 1) + 1;
            } else if (current == '*') {
                final var doubleStar = index + 1 < path.length() && path.charAt(index + 1) == '*';
                tokens.add(doubleStar ? PathToken.DOUBLE_STAR : PathToken.STAR);
                index += doubleStar ? 2 : 1;
            } else {
                tokens.add(PathToken.literal(current));
                index++;
            }
        }
        return tokens;
    }

    private static boolean pathsOverlap(List<PathToken> left, List<PathToken> right) {
        final var pending = new ArrayDeque<PathState>();
        final var visited = new HashSet<PathState>();
        pending.add(new PathState(0, 0));

        while (!pending.isEmpty()) {
            final var state = pending.removeFirst();
            if (!visited.add(state)) {
                continue;
            }
            if (state.left() == left.size() && state.right() == right.size()) {
                return true;
            }

            final var leftToken = state.left() < left.size() ? left.get(state.left()) : null;
            final var rightToken = state.right() < right.size() ? right.get(state.right()) : null;
            if (leftToken != null && leftToken.repeats()) {
                pending.add(new PathState(state.left() + 1, state.right()));
            }
            if (rightToken != null && rightToken.repeats()) {
                pending.add(new PathState(state.left(), state.right() + 1));
            }
            if (leftToken != null && rightToken != null && leftToken.overlaps(rightToken)) {
                pending.add(new PathState(
                        leftToken.repeats() ? state.left() : state.left() + 1,
                        rightToken.repeats() ? state.right() : state.right() + 1));
            }
        }
        return false;
    }

    private record PathState(int left, int right) {}

    private record PathToken(int literal, boolean matchesSlash, boolean repeats) {
        private static final PathToken NON_SLASH = new PathToken(-1, false, false);
        private static final PathToken STAR = new PathToken(-1, false, true);
        private static final PathToken DOUBLE_STAR = new PathToken(-1, true, true);

        private static PathToken literal(char value) {
            return new PathToken(value, false, false);
        }

        private boolean overlaps(PathToken other) {
            if (literal >= 0) {
                return other.literal >= 0 ? literal == other.literal : other.matchesSlash || literal != '/';
            }
            return other.literal < 0 || matchesSlash || other.literal != '/';
        }
    }
}
