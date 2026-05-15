package aussie.core.service.common;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;

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
 *
 * <p>Both JAX-RS ({@link ContainerRequestContext}) and Vert.x
 * ({@link HttpServerRequest}) call sites are supported through
 * overloads that share the same parsing logic.
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
        return extractInternal(request::getHeaderString, () -> extractFallbackFromJaxRs(request), socketIp);
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

    /**
     * Extract source identification from a Vert.x request using the socket
     * IP for trusted proxy validation.
     *
     * @param request  the Vert.x server request
     * @param socketIp the direct connection's remote IP address
     * @return source identifier containing IP address, host, and forwarded chain
     */
    public SourceIdentifier extract(HttpServerRequest request, String socketIp) {
        return extractInternal(request::getHeader, () -> extractFallbackFromVertx(request), socketIp);
    }

    private SourceIdentifier extractInternal(
            Function<String, String> headers, Supplier<String> fallbackIp, String socketIp) {
        final var trustHeaders = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);

        var ipAddress = trustHeaders ? extractIpFromHeaders(headers) : null;
        if (ipAddress == null || ipAddress.isEmpty()) {
            ipAddress = socketIp != null ? socketIp : fallbackIp.get();
        }

        final var host = trustHeaders ? extractHost(headers) : Optional.<String>empty();
        final var forwardedFor = trustHeaders ? extractForwardedFor(headers) : Optional.<String>empty();

        return new SourceIdentifier(ipAddress, host, forwardedFor);
    }

    private static String extractIpFromHeaders(Function<String, String> headers) {
        // Check X-Forwarded-For first (first IP in chain is original client)
        final var xForwardedFor = headers.apply("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // Check RFC 7239 Forwarded header
        final var forwarded = headers.apply("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            final var forParam = extractForwardedParam(forwarded, "for");
            if (forParam != null) {
                return forParam;
            }
        }

        // Fall back to X-Real-IP
        final var xRealIp = headers.apply("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }

        return null;
    }

    private static Optional<String> extractHost(Function<String, String> headers) {
        // Check X-Forwarded-Host first
        final var xForwardedHost = headers.apply("X-Forwarded-Host");
        if (xForwardedHost != null && !xForwardedHost.isEmpty()) {
            return Optional.of(xForwardedHost.split(",")[0].trim());
        }

        // Check RFC 7239 Forwarded header
        final var forwarded = headers.apply("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            final var hostParam = extractForwardedParam(forwarded, "host");
            if (hostParam != null) {
                return Optional.of(hostParam);
            }
        }

        // Fall back to Host header
        var host = headers.apply("Host");
        if (host != null && !host.isEmpty()) {
            // Remove port if present
            final var colonIdx = host.lastIndexOf(':');
            if (colonIdx > 0) {
                host = host.substring(0, colonIdx);
            }
            return Optional.of(host);
        }

        return Optional.empty();
    }

    private static Optional<String> extractForwardedFor(Function<String, String> headers) {
        final var xForwardedFor = headers.apply("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return Optional.of(xForwardedFor);
        }
        return Optional.empty();
    }

    private static String extractFallbackFromJaxRs(ContainerRequestContext request) {
        final var uriInfo = request.getUriInfo();
        if (uriInfo != null && uriInfo.getRequestUri() != null) {
            final var host = uriInfo.getRequestUri().getHost();
            if (host != null) {
                return host;
            }
        }
        return "unknown";
    }

    private static String extractFallbackFromVertx(HttpServerRequest request) {
        // authority() returns the parsed Host header without trailing port — parity
        // with JAX-RS UriInfo.getRequestUri().getHost().
        final var authority = request.authority();
        if (authority != null) {
            final var host = authority.host();
            if (host != null && !host.isEmpty()) {
                return host;
            }
        }
        return "unknown";
    }

    private static String extractForwardedParam(String forwarded, String param) {
        // Parse first entry in Forwarded header (original client)
        final var entries = forwarded.split(",");
        if (entries.length == 0) {
            return null;
        }

        final var firstEntry = entries[0].trim();
        final var parts = firstEntry.split(";");

        for (var part : parts) {
            final var keyValue = part.trim().split("=", 2);
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
