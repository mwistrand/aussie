package aussie.adapter.out.http;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import aussie.core.port.out.ForwardedHeaderBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * Builds legacy X-Forwarded-* headers.
 * - X-Forwarded-For: client IP address
 * - X-Forwarded-Host: original Host header
 * - X-Forwarded-Proto: original protocol (http/https)
 */
@ApplicationScoped
public class XForwardedHeaderBuilder implements ForwardedHeaderBuilder {

    @Override
    public Map<String, String> buildHeaders(ContainerRequestContext originalRequest, URI targetUri) {
        Map<String, String> headers = new HashMap<>();

        // X-Forwarded-For - client IP (append to existing if present)
        var clientIp = extractClientIp(originalRequest);
        var existingXff = originalRequest.getHeaderString("X-Forwarded-For");
        if (clientIp != null) {
            if (existingXff != null && !existingXff.isEmpty()) {
                headers.put("X-Forwarded-For", existingXff + ", " + clientIp);
            } else {
                headers.put("X-Forwarded-For", clientIp);
            }
        }

        // X-Forwarded-Host - original host
        var host = originalRequest.getHeaderString("Host");
        if (host != null) {
            headers.put("X-Forwarded-Host", host);
        }

        // X-Forwarded-Proto - original protocol
        var proto = extractProtocol(originalRequest);
        if (proto != null) {
            headers.put("X-Forwarded-Proto", proto);
        }

        return headers;
    }

    private String extractClientIp(ContainerRequestContext request) {
        // Check existing X-Forwarded-For
        var xForwardedFor = request.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // Fall back to request URI host
        var uriInfo = request.getUriInfo();
        if (uriInfo != null && uriInfo.getRequestUri() != null) {
            return uriInfo.getRequestUri().getHost();
        }

        return null;
    }

    private String extractProtocol(ContainerRequestContext request) {
        // Check existing X-Forwarded-Proto
        var xForwardedProto = request.getHeaderString("X-Forwarded-Proto");
        if (xForwardedProto != null && !xForwardedProto.isEmpty()) {
            return xForwardedProto;
        }

        // Fall back to request URI scheme
        var uriInfo = request.getUriInfo();
        if (uriInfo != null && uriInfo.getRequestUri() != null) {
            return uriInfo.getRequestUri().getScheme();
        }

        return "http";
    }
}
