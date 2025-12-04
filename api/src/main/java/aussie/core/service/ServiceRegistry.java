package aussie.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.EndpointConfig;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;

@ApplicationScoped
public class ServiceRegistry {

    private final Map<String, ServiceRegistration> services = new ConcurrentHashMap<>();
    private final Map<String, CompiledRoute> compiledRoutes = new ConcurrentHashMap<>();

    public void register(ServiceRegistration service) {
        services.put(service.serviceId(), service);
        for (var endpoint : service.endpoints()) {
            var routeKey = buildRouteKey(service.serviceId(), endpoint.path());
            var pattern = compilePathPattern(endpoint.path());
            compiledRoutes.put(routeKey, new CompiledRoute(service, endpoint, pattern));
        }
    }

    public void unregister(String serviceId) {
        var service = services.remove(serviceId);
        if (service != null) {
            for (var endpoint : service.endpoints()) {
                var routeKey = buildRouteKey(serviceId, endpoint.path());
                compiledRoutes.remove(routeKey);
            }
        }
    }

    public Optional<ServiceRegistration> getService(String serviceId) {
        return Optional.ofNullable(services.get(serviceId));
    }

    public List<ServiceRegistration> getAllServices() {
        return new ArrayList<>(services.values());
    }

    public Optional<RouteMatch> findRoute(String path, String method) {
        var normalizedPath = normalizePath(path);
        var upperMethod = method.toUpperCase();

        for (var route : compiledRoutes.values()) {
            if (!route.endpoint().methods().contains(upperMethod)
                    && !route.endpoint().methods().contains("*")) {
                continue;
            }

            var matcher = route.pattern().matcher(normalizedPath);
            if (matcher.matches()) {
                var pathVariables = extractPathVariables(route.endpoint().path(), matcher);
                var targetPath = route.endpoint()
                        .pathRewrite()
                        .map(rewrite -> applyPathRewrite(rewrite, pathVariables, normalizedPath))
                        .orElse(normalizedPath);

                return Optional.of(new RouteMatch(route.service(), route.endpoint(), targetPath, pathVariables));
            }
        }

        return Optional.empty();
    }

    private String buildRouteKey(String serviceId, String path) {
        return serviceId + ":" + path;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return path;
    }

    private Pattern compilePathPattern(String pathTemplate) {
        // Convert path template with {param} placeholders to regex
        var regex = pathTemplate
                .replaceAll("\\{([^/]+)\\}", "(?<$1>[^/]+)")
                .replaceAll("\\*\\*", ".*")
                .replaceAll("(?<!\\.)\\*", "[^/]*");

        return Pattern.compile("^" + regex + "$");
    }

    private Map<String, String> extractPathVariables(String pathTemplate, Matcher matcher) {
        var variables = new ConcurrentHashMap<String, String>();
        var paramPattern = Pattern.compile("\\{([^/]+)\\}");
        var paramMatcher = paramPattern.matcher(pathTemplate);

        while (paramMatcher.find()) {
            var paramName = paramMatcher.group(1);
            try {
                var value = matcher.group(paramName);
                if (value != null) {
                    variables.put(paramName, value);
                }
            } catch (IllegalArgumentException e) {
                // Group not found, skip
            }
        }

        return variables;
    }

    private String applyPathRewrite(String rewritePattern, Map<String, String> pathVariables, String originalPath) {
        var result = rewritePattern;
        for (var entry : pathVariables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private record CompiledRoute(ServiceRegistration service, EndpointConfig endpoint, Pattern pattern) {}
}
