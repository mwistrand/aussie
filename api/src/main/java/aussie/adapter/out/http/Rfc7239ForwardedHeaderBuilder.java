package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.port.out.ForwardedHeaderBuilder;
import aussie.core.service.common.ClientIpExtractor;

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
        var clientIp = ClientIpExtractor.extract(originalRequest);
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

    private String extractProtocol(GatewayRequest request) {
        // Check existing Forwarded header
        var forwarded = request.getHeaderString("Forwarded");
        if (forwarded != null) {
            var proto = ClientIpExtractor.extractForwardedParam(forwarded, "proto");
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
