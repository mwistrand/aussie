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

    /** Forwarding metadata is always rebuilt from the canonical inbound context. */
    private static final Set<String> INBOUND_FORWARDING_HEADERS = Set.of(
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-port", "x-forwarded-proto", "x-real-ip");

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

            if (shouldSkipHeader(headerName, dynamicHopByHop)) {
                continue;
            }

            // Share the list reference: the value list is read, never mutated downstream,
            // so List.copyOf would just churn the allocator with no observable benefit.
            headers.put(headerName, entry.getValue());
        }
    }

    /**
     * Case-insensitive check against the static hop-by-hop set, the dynamic set parsed
     * from {@code Connection}, and a couple of always-skipped headers ({@code Host},
     * {@code Content-Length}). Avoids the per-header {@code toLowerCase()} allocation
     * the previous implementation paid on every header in every request.
     */
    private boolean shouldSkipHeader(String headerName, Set<String> dynamicHopByHop) {
        for (final var hop : HOP_BY_HOP_HEADERS) {
            if (hop.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        for (final var forwarding : INBOUND_FORWARDING_HEADERS) {
            if (forwarding.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        if (!dynamicHopByHop.isEmpty()) {
            for (final var hop : dynamicHopByHop) {
                if (hop.equalsIgnoreCase(headerName)) {
                    return true;
                }
            }
        }
        return "host".equalsIgnoreCase(headerName) || "content-length".equalsIgnoreCase(headerName);
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
        final var filtered = new HashMap<String, List<String>>(responseHeaders.size());
        for (var entry : responseHeaders.entrySet()) {
            if (isHopByHop(entry.getKey(), dynamicHopByHop)) {
                continue;
            }
            // Share the list reference; the upstream response map is built per request and
            // is not mutated after this call returns.
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    private boolean isHopByHop(String headerName, Set<String> dynamicHopByHop) {
        for (final var hop : HOP_BY_HOP_HEADERS) {
            if (hop.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        if (dynamicHopByHop.isEmpty()) {
            return false;
        }
        for (final var hop : dynamicHopByHop) {
            if (hop.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
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

    /**
     * Look up the {@code Connection} header. Tries the two common cases first; if those
     * miss and the source map happens to be case-sensitive, falls back to a single
     * case-insensitive scan over the keys.
     */
    private List<String> findConnectionHeader(Map<String, List<String>> headers) {
        var values = headers.get("Connection");
        if (values != null) {
            return values;
        }
        values = headers.get("connection");
        if (values != null) {
            return values;
        }
        for (final var key : headers.keySet()) {
            if ("connection".equalsIgnoreCase(key)) {
                return headers.get(key);
            }
        }
        return List.of();
    }
}
