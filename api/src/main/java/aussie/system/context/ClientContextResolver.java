package aussie.system.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;

import aussie.core.service.common.TrustedProxyValidator;

/**
 * Computes a {@link ClientContext} from a request and caches it on the
 * {@link ContainerRequestContext} so subsequent filters reuse the same value.
 *
 * <p>Without this, {@link aussie.system.filter.RateLimitFilter} and
 * {@link aussie.system.filter.AuthRateLimitFilter} each re-call
 * {@link TrustedProxyValidator} and re-split the {@code Forwarded} /
 * {@code X-Forwarded-For} header values per request.
 */
@ApplicationScoped
public class ClientContextResolver {

    public static final String CONTEXT_PROPERTY = "aussie.client.context";

    private final TrustedProxyValidator trustedProxyValidator;

    @Inject
    public ClientContextResolver(TrustedProxyValidator trustedProxyValidator) {
        this.trustedProxyValidator = trustedProxyValidator;
    }

    /**
     * Compute the context from the incoming Vert.x request.
     */
    public ClientContext resolve(HttpServerRequest request) {
        final var remoteAddress = request.remoteAddress();
        final var socketIp = remoteAddress != null ? remoteAddress.host() : null;
        final var trust = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);
        final var forwardedClientIp = trust ? extractForwardedClientIp(request) : null;
        return new ClientContext(socketIp, trust, forwardedClientIp);
    }

    /**
     * Read the cached context from a request, falling back to computing one
     * (and stashing it) when the bootstrap filter has not yet run for this request.
     *
     * <p>The check-then-set is unsynchronised; safe because a Quarkus reactive
     * request is processed serially on the event loop, so two filters cannot
     * race on the same {@link ContainerRequestContext}.
     */
    public ClientContext getOrCompute(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        final var existing = (ClientContext) requestContext.getProperty(CONTEXT_PROPERTY);
        if (existing != null) {
            return existing;
        }
        final var ctx = resolve(vertxRequest);
        requestContext.setProperty(CONTEXT_PROPERTY, ctx);
        return ctx;
    }

    private String extractForwardedClientIp(HttpServerRequest request) {
        final var forwarded = request.getHeader("Forwarded");
        if (forwarded != null) {
            final var ip = parseForwardedFor(forwarded);
            if (ip != null) {
                return ip;
            }
        }

        final var xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        return null;
    }

    /**
     * Parse the client IP from the first entry of an RFC 7239 {@code Forwarded} header.
     * Mirrors the previous implementations in {@code RateLimitFilter} and
     * {@code AuthRateLimitFilter}, which were duplicated character-for-character.
     */
    private String parseForwardedFor(String forwarded) {
        final var firstEntry = forwarded.split(",")[0].trim();

        for (final var part : firstEntry.split(";")) {
            final var trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("for=")) {
                var value = trimmed.substring(4);
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.startsWith("[")) {
                    final var bracketEnd = value.indexOf(']');
                    if (bracketEnd > 0) {
                        return value.substring(1, bracketEnd);
                    }
                }
                final var colonCount = value.length() - value.replace(":", "").length();
                if (colonCount == 1) {
                    final var colonIndex = value.indexOf(':');
                    value = value.substring(0, colonIndex);
                }
                return value;
            }
        }
        return null;
    }
}
