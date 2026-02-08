package aussie.core.service.common;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import aussie.core.model.common.SourceIdentifier;

/**
 * Extract client source identification from HTTP requests.
 *
 * <p>Determines the original client IP address and host by examining
 * proxy headers in priority order:
 * <ol>
 *   <li>X-Forwarded-For (first IP in chain)</li>
 *   <li>RFC 7239 Forwarded header</li>
 *   <li>X-Real-IP</li>
 *   <li>Socket remote address (fallback)</li>
 * </ol>
 *
 * <p>Delegates to {@link TrustedProxyValidator} to determine whether
 * forwarding headers should be trusted for a given connection.
 */
@ApplicationScoped
public class SourceIdentifierExtractor {

    private final TrustedProxyValidator trustedProxyValidator;

    @Inject
    public SourceIdentifierExtractor(TrustedProxyValidator trustedProxyValidator) {
        this.trustedProxyValidator = trustedProxyValidator;
    }

    /**
     * Extract source identification from the request using the socket IP
     * for trusted proxy validation.
     *
     * @param request  the JAX-RS request context
     * @param socketIp the direct connection's remote IP address
     * @return source identifier containing IP address, host, and forwarded chain
     */
    public SourceIdentifier extract(ContainerRequestContext request, String socketIp) {
        final var trustHeaders = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);

        var ipAddress = trustHeaders ? extractIpFromHeaders(request) : null;
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = socketIp != null ? socketIp : extractFallbackIp(request);
        }

        var host = trustHeaders ? extractHost(request) : Optional.<String>empty();
        var forwardedFor = trustHeaders ? extractForwardedFor(request) : Optional.<String>empty();

        return new SourceIdentifier(ipAddress, host, forwardedFor);
    }

    /**
     * Extract source identification trusting all forwarding headers.
     * Prefer {@link #extract(ContainerRequestContext, String)} when the
     * socket IP is available.
     *
     * @param request the JAX-RS request context
     * @return source identifier containing IP address, host, and forwarded chain
     */
    public SourceIdentifier extract(ContainerRequestContext request) {
        return extract(request, null);
    }

    private String extractIpFromHeaders(ContainerRequestContext request) {
        // Check X-Forwarded-For first (first IP in chain is original client)
        var xForwardedFor = request.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // Check RFC 7239 Forwarded header
        var forwarded = request.getHeaderString("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            var forParam = extractForwardedParam(forwarded, "for");
            if (forParam != null) {
                return forParam;
            }
        }

        // Fall back to X-Real-IP
        var xRealIp = request.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }

        return null;
    }

    private String extractFallbackIp(ContainerRequestContext request) {
        var uriInfo = request.getUriInfo();
        if (uriInfo != null && uriInfo.getRequestUri() != null) {
            var host = uriInfo.getRequestUri().getHost();
            if (host != null) {
                return host;
            }
        }
        return "unknown";
    }

    private Optional<String> extractHost(ContainerRequestContext request) {
        // Check X-Forwarded-Host first
        var xForwardedHost = request.getHeaderString("X-Forwarded-Host");
        if (xForwardedHost != null && !xForwardedHost.isEmpty()) {
            return Optional.of(xForwardedHost.split(",")[0].trim());
        }

        // Check RFC 7239 Forwarded header
        var forwarded = request.getHeaderString("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            var hostParam = extractForwardedParam(forwarded, "host");
            if (hostParam != null) {
                return Optional.of(hostParam);
            }
        }

        // Fall back to Host header
        var host = request.getHeaderString("Host");
        if (host != null && !host.isEmpty()) {
            // Remove port if present
            var colonIdx = host.lastIndexOf(':');
            if (colonIdx > 0) {
                host = host.substring(0, colonIdx);
            }
            return Optional.of(host);
        }

        return Optional.empty();
    }

    private Optional<String> extractForwardedFor(ContainerRequestContext request) {
        var xForwardedFor = request.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return Optional.of(xForwardedFor);
        }
        return Optional.empty();
    }

    private String extractForwardedParam(String forwarded, String param) {
        // Parse first entry in Forwarded header (original client)
        var entries = forwarded.split(",");
        if (entries.length == 0) {
            return null;
        }

        var firstEntry = entries[0].trim();
        var parts = firstEntry.split(";");

        for (var part : parts) {
            var keyValue = part.trim().split("=", 2);
            if (keyValue.length == 2 && keyValue[0].equalsIgnoreCase(param)) {
                var value = keyValue[1];
                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }

        return null;
    }
}
