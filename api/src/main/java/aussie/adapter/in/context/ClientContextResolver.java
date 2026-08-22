package aussie.adapter.in.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

import aussie.common.context.ClientContext;
import aussie.core.service.common.IpNetwork;
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
    private static final int MAX_FORWARDING_HEADER_LENGTH = 8192;
    private static final int MAX_FORWARDING_HOPS = 16;

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
        if (!trust) {
            return new ClientContext(socketIp, false, null, null);
        }
        return resolveTrusted(
                socketIp,
                request.getHeader("Forwarded"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("X-Forwarded-Proto"));
    }

    /**
     * Compute the context from request primitives without requiring a running HTTP server.
     * This is also the pure entry point used to benchmark the per-request parsing path.
     */
    public ClientContext resolve(
            String socketIp, String forwarded, String xForwardedFor, String xRealIp, String xForwardedProto) {
        final var trust = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);
        if (!trust) {
            return new ClientContext(socketIp, false, null, null);
        }
        return resolveTrusted(socketIp, forwarded, xForwardedFor, xRealIp, xForwardedProto);
    }

    private ClientContext resolveTrusted(
            String socketIp, String forwarded, String xForwardedFor, String xRealIp, String xForwardedProto) {
        final var forwardedClientIp = extractForwardedClientIp(forwarded, xForwardedFor, xRealIp);
        final var externalScheme = extractExternalScheme(forwarded, xForwardedProto);
        return new ClientContext(socketIp, true, forwardedClientIp, externalScheme);
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

    /**
     * Read the canonical context attached to the Vert.x routing context, computing it
     * once when this is the first request-path consumer (notably WebSocket filters).
     */
    public ClientContext getOrCompute(RoutingContext routingContext) {
        final var existing = routingContext.<ClientContext>get(CONTEXT_PROPERTY);
        if (existing != null) {
            return existing;
        }
        final var ctx = resolve(routingContext.request());
        routingContext.put(CONTEXT_PROPERTY, ctx);
        return ctx;
    }

    private String extractForwardedClientIp(String forwarded, String xForwardedFor, String xRealIp) {
        if (forwarded != null && !forwarded.isBlank()) {
            final var chain = parseForwardedChain(forwarded);
            return chain.isEmpty() ? null : resolveRightmostUntrusted(chain);
        }

        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return resolveRightmostUntrusted(parseXForwardedForChain(xForwardedFor));
        }

        if (xRealIp != null && !xRealIp.isBlank() && xRealIp.length() <= MAX_FORWARDING_HEADER_LENGTH) {
            return normalizeIpLiteral(xRealIp.trim());
        }

        return null;
    }

    private String extractExternalScheme(String forwarded, String xForwardedProto) {
        if (forwarded != null && !forwarded.isBlank()) {
            return parseForwardedScheme(forwarded);
        }
        return parseXForwardedProto(xForwardedProto);
    }

    private String parseForwardedScheme(String header) {
        if (header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return null;
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return null;
        }

        for (final var entry : entries) {
            String scheme = null;
            for (final var part : entry.split(";", -1)) {
                final var trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "proto=", 0, 6)) {
                    if (scheme != null) {
                        return null;
                    }
                    scheme = normalizeScheme(trimmed.substring(6));
                    if (scheme == null) {
                        return null;
                    }
                }
            }
            if (scheme != null) {
                return scheme;
            }
        }
        return null;
    }

    private String parseXForwardedProto(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return null;
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return null;
        }

        String externalScheme = null;
        for (final var entry : entries) {
            final var scheme = normalizeScheme(entry);
            if (scheme == null) {
                return null;
            }
            if (externalScheme == null) {
                externalScheme = scheme;
            }
        }
        return externalScheme;
    }

    private String normalizeScheme(String rawValue) {
        var value = rawValue.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if ("http".equalsIgnoreCase(value) || "https".equalsIgnoreCase(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private List<String> parseForwardedChain(String header) {
        if (header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return List.of();
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return List.of();
        }

        final var chain = new ArrayList<String>(entries.length);
        for (final var entry : entries) {
            String address = null;
            for (final var part : entry.split(";", -1)) {
                final var trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "for=", 0, 4)) {
                    if (address != null) {
                        return List.of();
                    }
                    address = normalizeForwardedNode(trimmed.substring(4));
                }
            }
            if (address == null) {
                return List.of();
            }
            chain.add(address);
        }
        return List.copyOf(chain);
    }

    private List<String> parseXForwardedForChain(String header) {
        if (header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return List.of();
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return List.of();
        }

        final var chain = new ArrayList<String>(entries.length);
        for (final var entry : entries) {
            final var address = normalizeIpLiteral(entry.trim());
            if (address == null) {
                return List.of();
            }
            chain.add(address);
        }
        return List.copyOf(chain);
    }

    private String resolveRightmostUntrusted(List<String> chain) {
        for (var i = chain.size() - 1; i >= 0; i--) {
            final var address = chain.get(i);
            if (!trustedProxyValidator.isTrustedProxy(address)) {
                return address;
            }
        }
        return chain.isEmpty() ? null : chain.getFirst();
    }

    private String normalizeForwardedNode(String rawValue) {
        var value = rawValue.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("_") || "unknown".equalsIgnoreCase(value)) {
            return null;
        }
        if (value.startsWith("[")) {
            final var bracketEnd = value.indexOf(']');
            if (bracketEnd <= 1) {
                return null;
            }
            final var suffix = value.substring(bracketEnd + 1);
            if (!suffix.isEmpty() && (!suffix.startsWith(":") || !isValidPort(suffix.substring(1)))) {
                return null;
            }
            value = value.substring(1, bracketEnd);
        } else if (value.chars().filter(c -> c == ':').count() == 1) {
            final var colonIndex = value.indexOf(':');
            final var suffix = value.substring(colonIndex + 1);
            if (!isValidPort(suffix)) {
                return null;
            }
            value = value.substring(0, colonIndex);
        }
        return normalizeIpLiteral(value);
    }

    private String normalizeIpLiteral(String value) {
        return IpNetwork.parse(value)
                .filter(IpNetwork::isExactAddress)
                .map(ignored -> value.toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    private boolean isValidPort(String value) {
        if (!value.matches("\\d{1,5}")) {
            return false;
        }
        return Integer.parseInt(value) <= 65535;
    }
}
