package aussie.core.service;

import java.util.Optional;

import aussie.core.model.SourceIdentifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;

@ApplicationScoped
public class SourceIdentifierExtractor {

    public SourceIdentifier extract(ContainerRequestContext request) {
        var ipAddress = extractIpAddress(request);
        var host = extractHost(request);
        var forwardedFor = extractForwardedFor(request);

        return new SourceIdentifier(ipAddress, host, forwardedFor);
    }

    private String extractIpAddress(ContainerRequestContext request) {
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

        // Last resort: use request URI host (in production, would use socket address)
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
