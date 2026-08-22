package aussie.common.context;

/**
 * Pre-resolved client identification for the duration of a single request.
 *
 * <p>Computed once at the inbound boundary so downstream consumers do not each
 * re-parse forwarded headers or re-walk the trusted-proxy CIDR list.
 *
 * @param socketIp                the direct connection's remote IP address (may be null)
 * @param trustForwardingHeaders  whether the socket IP belongs to a trusted proxy and
 *                                forwarded headers should be honored
 * @param forwardedClientIp       the client IP parsed from {@code Forwarded},
 *                                {@code X-Forwarded-For}, or {@code X-Real-IP}; null when
 *                                unavailable or the connection is not from a trusted proxy
 * @param externalScheme          the validated external {@code http} or {@code https}
 *                                scheme supplied by a trusted proxy; null when unavailable
 */
public record ClientContext(
        String socketIp, boolean trustForwardingHeaders, String forwardedClientIp, String externalScheme) {

    public ClientContext(String socketIp, boolean trustForwardingHeaders, String forwardedClientIp) {
        this(socketIp, trustForwardingHeaders, forwardedClientIp, null);
    }

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
