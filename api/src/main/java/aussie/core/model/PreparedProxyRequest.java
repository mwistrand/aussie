package aussie.core.model;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * A fully prepared proxy request with all headers processed according to business rules.
 * This includes:
 * - Original headers with hop-by-hop headers removed
 * - Host header set for target
 * - Forwarding headers (X-Forwarded-* or Forwarded) added
 */
public record PreparedProxyRequest(String method, URI targetUri, Map<String, List<String>> headers, byte[] body) {

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
    }
}
