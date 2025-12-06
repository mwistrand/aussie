package aussie.core.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.AussieToken;
import aussie.core.model.GatewayRequest;
import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.RouteMatch;
import aussie.core.port.out.ForwardedHeaderBuilderProvider;

/**
 * Prepares proxy requests by applying header filtering and forwarding rules.
 * This encapsulates the business logic for:
 * - Filtering hop-by-hop headers (RFC 2616 Section 13.5.1)
 * - Setting the Host header for the target
 * - Adding forwarding headers (X-Forwarded-* or RFC 7239 Forwarded)
 */
@ApplicationScoped
public class ProxyRequestPreparer {

    /**
     * HTTP hop-by-hop headers that must not be forwarded to the upstream server.
     * These are connection-specific headers per RFC 2616 Section 13.5.1.
     */
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final ForwardedHeaderBuilderProvider headerBuilderProvider;

    @Inject
    public ProxyRequestPreparer(ForwardedHeaderBuilderProvider headerBuilderProvider) {
        this.headerBuilderProvider = headerBuilderProvider;
    }

    public PreparedProxyRequest prepare(GatewayRequest request, RouteMatch route) {
        return prepare(request, route, Optional.empty());
    }

    /**
     * Prepare a proxy request with optional Aussie token for authenticated routes.
     *
     * @param request the gateway request
     * @param route   the matched route
     * @param token   optional Aussie token to include in Authorization header
     * @return prepared proxy request
     */
    public PreparedProxyRequest prepare(GatewayRequest request, RouteMatch route, Optional<AussieToken> token) {
        var targetUri = route.targetUri();
        var headers = buildHeaders(request, targetUri);

        // Set Authorization header with Aussie token if present
        token.ifPresent(t -> headers.put("Authorization", List.of("Bearer " + t.jws())));

        return new PreparedProxyRequest(request.method(), targetUri, headers, request.body());
    }

    private Map<String, List<String>> buildHeaders(GatewayRequest request, URI targetUri) {
        Map<String, List<String>> headers = new HashMap<>();

        copyFilteredHeaders(request, headers);
        setHostHeader(headers, targetUri);
        addForwardingHeaders(request, targetUri, headers);
        addViaHeader(request, headers);

        return headers;
    }

    private void copyFilteredHeaders(GatewayRequest request, Map<String, List<String>> headers) {
        for (var entry : request.headers().entrySet()) {
            var headerName = entry.getKey();
            var lowerName = headerName.toLowerCase();

            if (shouldSkipHeader(lowerName)) {
                continue;
            }

            headers.put(headerName, new ArrayList<>(entry.getValue()));
        }
    }

    private boolean shouldSkipHeader(String lowerName) {
        // Skip hop-by-hop headers
        if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
            return true;
        }
        // Skip Host header (will be set for target)
        if ("host".equals(lowerName)) {
            return true;
        }
        // Skip Content-Length as it will be set by the HTTP client
        if ("content-length".equals(lowerName)) {
            return true;
        }
        return false;
    }

    private void setHostHeader(Map<String, List<String>> headers, URI targetUri) {
        var port = targetUri.getPort();
        var host = targetUri.getHost();
        if (port != -1 && port != 80 && port != 443) {
            host += ":" + port;
        }
        headers.put("Host", List.of(host));
    }

    private void addForwardingHeaders(GatewayRequest request, URI targetUri, Map<String, List<String>> headers) {
        var forwardingHeaders = headerBuilderProvider.getBuilder().buildHeaders(request, targetUri);
        for (var entry : forwardingHeaders.entrySet()) {
            headers.put(entry.getKey(), List.of(entry.getValue()));
        }
    }

    /**
     * Adds the Via header per RFC 7230 to indicate the request passed through this proxy.
     */
    private void addViaHeader(GatewayRequest request, Map<String, List<String>> headers) {
        var requestHost = request.requestUri() != null ? request.requestUri().getHost() : "aussie";
        var viaValue = "1.1 " + (requestHost != null ? requestHost : "aussie") + " (Aussie)";

        // Check for existing Via header and append
        var existingVia = headers.get("Via");
        if (existingVia != null && !existingVia.isEmpty()) {
            viaValue = existingVia.get(0) + ", " + viaValue;
        }

        headers.put("Via", List.of(viaValue));
    }

    /**
     * Filters hop-by-hop headers from a response.
     * Call this when processing upstream responses before returning to the client.
     */
    public Map<String, List<String>> filterResponseHeaders(Map<String, List<String>> responseHeaders) {
        Map<String, List<String>> filtered = new HashMap<>();
        for (var entry : responseHeaders.entrySet()) {
            var lowerName = entry.getKey().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(lowerName)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
