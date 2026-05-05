package aussie.system.context;

/**
 * Pre-resolved client identification for the duration of a single request.
 *
 * <p>Computed once in {@link ClientContextFilter} so downstream filters
 * (rate limiting, auth rate limiting, access control) do not each re-parse
 * forwarded headers or re-walk the trusted-proxy CIDR list.
 *
 * @param socketIp                the direct connection's remote IP address (may be null)
 * @param trustForwardingHeaders  whether the socket IP belongs to a trusted proxy and
 *                                forwarded headers should be honored
 * @param forwardedClientIp       the client IP parsed from {@code Forwarded} or
 *                                {@code X-Forwarded-For}, or null when unavailable or
 *                                the connection is not from a trusted proxy
 */
public record ClientContext(String socketIp, boolean trustForwardingHeaders, String forwardedClientIp) {

    /**
     * The effective client IP for rate-limit keying and source identification.
     * Prefers a trusted forwarded value, then the socket IP, then the fixed sentinel
     * {@code "unknown"} so callers never need a null check.
     */
    public String resolvedIp() {
        if (forwardedClientIp != null) {
            return forwardedClientIp;
        }
        return socketIp != null ? socketIp : "unknown";
    }
}
