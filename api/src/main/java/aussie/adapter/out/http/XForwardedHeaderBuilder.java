package aussie.adapter.out.http;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.port.out.ForwardedHeaderBuilder;

/**
 * Build legacy X-Forwarded-* headers.
 * - X-Forwarded-For: client IP address
 * - X-Forwarded-Host: original Host header
 * - X-Forwarded-Proto: original protocol (http/https)
 */
@ApplicationScoped
public class XForwardedHeaderBuilder implements ForwardedHeaderBuilder {

    @Override
    public Map<String, String> buildHeaders(GatewayRequest originalRequest, URI targetUri) {
        Map<String, String> headers = new HashMap<>();

        // The request's clientIp was resolved once at the inbound trust boundary.
        // Never preserve an untrusted caller-supplied forwarding chain.
        var clientIp = originalRequest.clientIp();
        if (clientIp != null) {
            headers.put("X-Forwarded-For", clientIp);
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
}
