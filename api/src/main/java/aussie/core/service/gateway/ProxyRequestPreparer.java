package aussie.core.service.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.routing.RouteMatch;
import aussie.core.port.out.ForwardedHeaderBuilderProvider;

/**
 * Prepare proxy requests by applying header filtering and forwarding rules.
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

    /**
     * Connection header directives that are not header names to strip.
     */
    private static final Set<String> CONNECTION_DIRECTIVES = Set.of("close", "keep-alive");

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
        // Preserve query string from original request
        var query = request.requestUri() != null ? request.requestUri().getRawQuery() : null;
        var targetUri = route.targetUri(query);
        var headers = buildHeaders(request, targetUri);

        // Set Authorization header with Aussie token if present
        token.ifPresent(t -> headers.put("Authorization", List.of("Bearer " + t.jws())));

        // Add X-Forwarded-Prefix to inform backend of the path prefix that was stripped
        var routePrefix = route.service().routePrefix();
        if (routePrefix != null && !routePrefix.isEmpty()) {
            headers.put("X-Forwarded-Prefix", List.of(routePrefix));
        }

        var effectiveTimeout = resolveTimeout(route);
        return new PreparedProxyRequest(request.method(), targetUri, headers, request.body(), effectiveTimeout);
    }

    /**
     * Resolve the effective request timeout for a route match.
     *
     * <p>Priority order: endpoint timeout &gt; service timeout &gt; empty (global default).
     */
    private Optional<Duration> resolveTimeout(RouteMatch route) {
        // Endpoint-level timeout takes highest priority
        final var endpointTimeout = route.endpointConfig().timeoutConfig().flatMap(tc -> tc.requestTimeout());
        if (endpointTimeout.isPresent()) {
            return endpointTimeout;
        }

        // Fall back to service-level timeout
        return route.service().timeoutConfig().flatMap(tc -> tc.requestTimeout());
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
        final var dynamicHopByHop = parseDynamicHopByHopHeaders(request.headers());

        for (var entry : request.headers().entrySet()) {
            final var headerName = entry.getKey();
            final var lowerName = headerName.toLowerCase();

            if (shouldSkipHeader(lowerName, dynamicHopByHop)) {
                continue;
            }

            // Use List.copyOf for efficient immutable copy - lists are only read, not modified
            headers.put(headerName, List.copyOf(entry.getValue()));
        }
    }

    private boolean shouldSkipHeader(String lowerName, Set<String> dynamicHopByHop) {
        // Skip hop-by-hop headers (static per RFC 2616 + dynamic from Connection header)
        if (HOP_BY_HOP_HEADERS.contains(lowerName) || dynamicHopByHop.contains(lowerName)) {
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
        final var dynamicHopByHop = parseDynamicHopByHopHeaders(responseHeaders);
        Map<String, List<String>> filtered = new HashMap<>();
        for (var entry : responseHeaders.entrySet()) {
            var lowerName = entry.getKey().toLowerCase();
            if (!HOP_BY_HOP_HEADERS.contains(lowerName) && !dynamicHopByHop.contains(lowerName)) {
                filtered.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return filtered;
    }

    /**
     * Parses the Connection header to discover dynamically-declared hop-by-hop headers.
     * Per RFC 2616 Section 14.10, the Connection header can list additional header names
     * that are hop-by-hop for the current connection only (e.g., "Connection: X-Custom-Header").
     * Connection directives like "close" and "keep-alive" are not header names to strip.
     */
    private Set<String> parseDynamicHopByHopHeaders(Map<String, List<String>> headers) {
        final var connectionValues = findConnectionHeader(headers);
        if (connectionValues.isEmpty()) {
            return Set.of();
        }

        var dynamicHeaders = new HashSet<String>();
        for (final var value : connectionValues) {
            for (final var token : value.split(",")) {
                final var trimmed = token.strip().toLowerCase();
                if (!trimmed.isEmpty() && !CONNECTION_DIRECTIVES.contains(trimmed)) {
                    dynamicHeaders.add(trimmed);
                }
            }
        }
        return dynamicHeaders;
    }

    private List<String> findConnectionHeader(Map<String, List<String>> headers) {
        for (final var entry : headers.entrySet()) {
            if ("connection".equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }
}
