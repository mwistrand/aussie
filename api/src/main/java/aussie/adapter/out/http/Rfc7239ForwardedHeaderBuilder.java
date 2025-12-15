package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.port.out.ForwardedHeaderBuilder;

/**
 * Build RFC 7239 compliant Forwarded header.
 * Format: Forwarded: for=192.0.2.60;proto=http;by=203.0.113.43;host=example.com
 */
@ApplicationScoped
public class Rfc7239ForwardedHeaderBuilder implements ForwardedHeaderBuilder {

    @Override
    public Map<String, String> buildHeaders(GatewayRequest originalRequest, URI targetUri) {
        var parts = new ArrayList<String>();

        // for - client IP address
        var clientIp = extractClientIp(originalRequest);
        if (clientIp != null) {
            parts.add("for=" + quoteIfNeeded(clientIp));
        }

        // proto - original protocol
        var proto = extractProtocol(originalRequest);
        if (proto != null) {
            parts.add("proto=" + proto);
        }

        // host - original Host header
        var host = originalRequest.getHeaderString("Host");
        if (host != null) {
            parts.add("host=" + quoteIfNeeded(host));
        }

        // by - gateway identifier (optional, could be configured)
        // For now, we skip 'by' as it requires gateway IP configuration

        var existingForwarded = originalRequest.getHeaderString("Forwarded");
        var newForwarded = String.join(";", parts);

        if (existingForwarded != null && !existingForwarded.isEmpty()) {
            newForwarded = existingForwarded + ", " + newForwarded;
        }

        return Map.of("Forwarded", newForwarded);
    }

    private String extractClientIp(GatewayRequest request) {
        // First check if there's already a Forwarded header with 'for'
        var forwarded = request.getHeaderString("Forwarded");
        if (forwarded != null) {
            var forMatch = extractForwardedParam(forwarded, "for");
            if (forMatch != null) {
                return forMatch;
            }
        }

        // Check X-Forwarded-For as fallback
        var xForwardedFor = request.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // Fall back to request URI host (in real impl, would get from socket)
        var requestUri = request.requestUri();
        if (requestUri != null) {
            return requestUri.getHost();
        }

        return null;
    }

    private String extractProtocol(GatewayRequest request) {
        // Check existing Forwarded header
        var forwarded = request.getHeaderString("Forwarded");
        if (forwarded != null) {
            var proto = extractForwardedParam(forwarded, "proto");
            if (proto != null) {
                return proto;
            }
        }

        // Check X-Forwarded-Proto
        var xForwardedProto = request.getHeaderString("X-Forwarded-Proto");
        if (xForwardedProto != null && !xForwardedProto.isEmpty()) {
            return xForwardedProto;
        }

        // Fall back to request URI scheme
        var requestUri = request.requestUri();
        if (requestUri != null) {
            return requestUri.getScheme();
        }

        return "http";
    }

    private String extractForwardedParam(String forwarded, String param) {
        // Parse last entry in Forwarded header
        var entries = forwarded.split(",");
        if (entries.length == 0) {
            return null;
        }

        var lastEntry = entries[entries.length - 1].trim();
        var parts = lastEntry.split(";");

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

    private String quoteIfNeeded(String value) {
        // Quote values containing special characters per RFC 7239
        if (value.contains(":")
                || value.contains("[")
                || value.contains("]")
                || value.contains(";")
                || value.contains(",")
                || value.contains(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}
