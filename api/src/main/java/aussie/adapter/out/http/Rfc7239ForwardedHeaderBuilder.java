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
        var clientIp = originalRequest.clientIp();
        if (clientIp != null) {
            parts.add("for=" + formatNodeIdentifier(clientIp));
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

        var newForwarded = String.join(";", parts);
        return Map.of("Forwarded", newForwarded);
    }

    private String extractProtocol(GatewayRequest request) {
        if (request.externalScheme() != null) {
            return request.externalScheme();
        }

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

    private String formatNodeIdentifier(String clientIp) {
        if (clientIp.indexOf(':') >= 0 && !clientIp.startsWith("[")) {
            return quoteIfNeeded("[" + clientIp + "]");
        }
        return quoteIfNeeded(clientIp);
    }
}
