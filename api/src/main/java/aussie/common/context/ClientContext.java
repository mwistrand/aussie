package aussie.common.context;

import java.util.List;

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
 * @param socketPort              the direct connection's remote port; null when unavailable
 * @param forwardingChain         the bounded, normalized forwarding chain and trust decision
 *                                for each hop
 * @param externalHost            the validated externally visible host; null when unavailable
 * @param externalPort            the validated externally visible port; null when implicit
 * @param principalId             the stable principal ID populated only after authentication
 * @param credentialId            the stable credential ID populated only after authentication
 * @param correlationId           the bounded request correlation ID
 */
public record ClientContext(
        String socketIp,
        boolean trustForwardingHeaders,
        String forwardedClientIp,
        String externalScheme,
        Integer socketPort,
        List<ForwardingHop> forwardingChain,
        String externalHost,
        Integer externalPort,
        String principalId,
        String credentialId,
        String correlationId) {

    public ClientContext {
        forwardingChain = forwardingChain == null ? List.of() : List.copyOf(forwardingChain);
    }

    public ClientContext(String socketIp, boolean trustForwardingHeaders, String forwardedClientIp) {
        this(socketIp, trustForwardingHeaders, forwardedClientIp, null, null, List.of(), null, null, null, null, null);
    }

    public ClientContext(
            String socketIp, boolean trustForwardingHeaders, String forwardedClientIp, String externalScheme) {
        this(
                socketIp,
                trustForwardingHeaders,
                forwardedClientIp,
                externalScheme,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null);
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

    /** Return an authenticated copy while preserving the pre-authentication network boundary. */
    public ClientContext withVerifiedIdentity(String principalId, String credentialId) {
        return new ClientContext(
                socketIp,
                trustForwardingHeaders,
                forwardedClientIp,
                externalScheme,
                socketPort,
                forwardingChain,
                externalHost,
                externalPort,
                principalId,
                credentialId,
                correlationId);
    }

    /** Render the canonical external authority for outbound forwarding metadata. */
    public String externalAuthority() {
        if (externalHost == null) {
            return null;
        }
        final var host = externalHost.indexOf(':') >= 0 ? "[" + externalHost + "]" : externalHost;
        return externalPort == null ? host : host + ":" + externalPort;
    }

    /** A normalized forwarding hop and whether it belongs to the configured trusted set. */
    public record ForwardingHop(String ip, boolean trusted) {}
}
