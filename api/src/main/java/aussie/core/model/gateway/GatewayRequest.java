package aussie.core.model.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import aussie.core.model.routing.RouteLookupResult;

public record GatewayRequest(
        String method,
        String path,
        Map<String, List<String>> headers,
        URI requestUri,
        byte[] body,
        String clientIp,
        String externalScheme,
        String externalHost,
        Integer externalPort,
        Optional<RouteLookupResult> resolvedRoute,
        boolean hasRouteSnapshot) {

    public GatewayRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            URI requestUri,
            byte[] body,
            String clientIp) {
        this(method, path, headers, requestUri, body, clientIp, null, null, null, Optional.empty(), false);
    }

    public GatewayRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            URI requestUri,
            byte[] body,
            String clientIp,
            String externalScheme) {
        this(method, path, headers, requestUri, body, clientIp, externalScheme, null, null, Optional.empty(), false);
    }

    public GatewayRequest(
            String method,
            String path,
            Map<String, List<String>> headers,
            URI requestUri,
            byte[] body,
            String clientIp,
            String externalScheme,
            String externalHost,
            Integer externalPort) {
        this(
                method,
                path,
                headers,
                requestUri,
                body,
                clientIp,
                externalScheme,
                externalHost,
                externalPort,
                Optional.empty(),
                false);
    }

    public GatewayRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        if (path == null) {
            path = "/";
        }
        if (headers == null) {
            headers = Map.of();
        }
        if (body == null) {
            body = new byte[0];
        }
        if (resolvedRoute == null) {
            resolvedRoute = Optional.empty();
        }
        if (resolvedRoute.isPresent()) {
            hasRouteSnapshot = true;
        }
    }

    public String getHeaderString(String name) {
        var values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    public String externalAuthority() {
        if (externalHost == null) {
            return null;
        }
        final var host = externalHost.indexOf(':') >= 0 ? "[" + externalHost + "]" : externalHost;
        return externalPort == null ? host : host + ":" + externalPort;
    }
}
