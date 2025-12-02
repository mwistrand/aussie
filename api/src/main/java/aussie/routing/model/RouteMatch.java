package aussie.routing.model;

import java.net.URI;
import java.util.Map;

public record RouteMatch(
    ServiceRegistration service,
    EndpointConfig endpoint,
    String targetPath,
    Map<String, String> pathVariables
) {
    public RouteMatch {
        if (service == null) {
            throw new IllegalArgumentException("Service cannot be null");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("Endpoint cannot be null");
        }
        if (targetPath == null) {
            targetPath = "";
        }
        if (pathVariables == null) {
            pathVariables = Map.of();
        }
    }

    /**
     * Returns the full target URI for this route match.
     */
    public URI targetUri() {
        var base = service.baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        var path = targetPath.startsWith("/") ? targetPath : "/" + targetPath;
        return URI.create(base + path);
    }
}
