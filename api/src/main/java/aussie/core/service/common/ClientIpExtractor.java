package aussie.core.service.common;

import aussie.core.model.gateway.GatewayRequest;

/**
 * Utility for extracting client IP address from gateway requests.
 *
 * <p>Checks headers in the following order:
 * <ol>
 *   <li>RFC 7239 {@code Forwarded} header's {@code for} parameter</li>
 *   <li>Legacy {@code X-Forwarded-For} header (first IP in chain)</li>
 *   <li>Socket connection's remote address ({@code clientIp})</li>
 * </ol>
 */
public final class ClientIpExtractor {

    private ClientIpExtractor() {}

    /**
     * Extract the original client IP address from a gateway request.
     *
     * @param request the gateway request
     * @return the client IP address, or null if not available
     */
    public static String extract(GatewayRequest request) {
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

        // Fall back to actual client IP from the socket connection
        return request.clientIp();
    }

    /**
     * Extract a parameter value from an RFC 7239 Forwarded header.
     *
     * @param forwarded the Forwarded header value
     * @param param the parameter name to extract (e.g., "for", "proto", "host")
     * @return the parameter value, or null if not found
     */
    public static String extractForwardedParam(String forwarded, String param) {
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
}
