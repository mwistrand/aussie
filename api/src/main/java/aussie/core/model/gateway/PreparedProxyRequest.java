package aussie.core.model.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fully prepared proxy request with all headers processed according to business rules.
 * This includes:
 * - Original headers with hop-by-hop headers removed
 * - Host header set for target
 * - Forwarding headers (X-Forwarded-* or Forwarded) added
 * - Effective request timeout (resolved from endpoint, service, or global config)
 *
 * @param method         the HTTP method
 * @param targetUri      the upstream service URI
 * @param headers        the prepared request headers
 * @param body           the request body
 * @param requestTimeout the effective request timeout, or empty to use global default
 */
public record PreparedProxyRequest(
        String method,
        URI targetUri,
        Map<String, List<String>> headers,
        byte[] body,
        Optional<Duration> requestTimeout) {

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
        if (requestTimeout == null) {
            requestTimeout = Optional.empty();
        }
    }

    /**
     * Convenience constructor without explicit timeout (uses global default).
     */
    public PreparedProxyRequest(String method, URI targetUri, Map<String, List<String>> headers, byte[] body) {
        this(method, targetUri, headers, body, Optional.empty());
    }
}
