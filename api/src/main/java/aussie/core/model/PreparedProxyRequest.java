package aussie.core.model;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A fully prepared proxy request with all headers processed according to business rules.
 *
 * <p>This includes:
 * <ul>
 *   <li>Original headers with hop-by-hop headers removed</li>
 *   <li>Host header set for target</li>
 *   <li>Forwarding headers (X-Forwarded-* or Forwarded) added</li>
 * </ul>
 *
 * @param serviceId identifier of the target service (for telemetry)
 * @param method HTTP method
 * @param targetUri target URI for the upstream request
 * @param headers prepared headers
 * @param body request body
 */
public record PreparedProxyRequest(
        String serviceId, String method, URI targetUri, Map<String, List<String>> headers, byte[] body) {

    public PreparedProxyRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        if (targetUri == null) {
            throw new IllegalArgumentException("targetUri is required");
        }
        if (headers == null) {
            headers = Map.of();
        }
        if (body == null) {
            body = new byte[0];
        }
        // serviceId can be null for unknown routes
    }

    /**
     * Creates a PreparedProxyRequest without a service ID (for backwards compatibility).
     */
    public PreparedProxyRequest(String method, URI targetUri, Map<String, List<String>> headers, byte[] body) {
        this(null, method, targetUri, headers, body);
    }
}
