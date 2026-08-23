package aussie.adapter.in.context;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

import aussie.common.context.ClientContext;
import aussie.common.context.ClientContext.ForwardingHop;
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
    private static final int MAX_AUTHORITY_LENGTH = 512;
    private static final int MAX_CORRELATION_ID_LENGTH = 128;

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
        final var socketPort = remoteAddress != null && remoteAddress.port() > 0 ? remoteAddress.port() : null;
        final var directScheme = request.isSSL() ? "https" : "http";
        final var directAuthority = parseAuthority(request.getHeader("Host"));
        final var correlationId = resolveCorrelationId(request.getHeader("X-Request-ID"));
        final var trust = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);
        if (!trust) {
            return context(socketIp, socketPort, false, List.of(), null, directScheme, directAuthority, correlationId);
        }
        return resolveTrusted(
                socketIp,
                socketPort,
                request.getHeader("Forwarded"),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getHeader("X-Forwarded-Proto"),
                request.getHeader("X-Forwarded-Host"),
                request.getHeader("X-Forwarded-Port"),
                directScheme,
                directAuthority,
                correlationId);
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
        return resolveTrusted(
                socketIp, null, forwarded, xForwardedFor, xRealIp, xForwardedProto, null, null, null, null, null);
    }

    private ClientContext resolveTrusted(
            String socketIp,
            Integer socketPort,
            String forwarded,
            String xForwardedFor,
            String xRealIp,
            String xForwardedProto,
            String xForwardedHost,
            String xForwardedPort,
            String directScheme,
            Authority directAuthority,
            String correlationId) {
        final var chain = extractForwardingChain(forwarded, xForwardedFor, xRealIp);
        final var forwardingHops = chain.stream()
                .map(ip -> new ForwardingHop(ip, trustedProxyValidator.isTrustedProxy(ip)))
                .toList();
        final var forwardedClientIp = resolveRightmostUntrusted(forwardingHops);
        final var externalScheme = extractExternalScheme(forwarded, xForwardedProto);
        var externalAuthority = extractExternalAuthority(forwarded, xForwardedHost, xForwardedPort);
        if (externalAuthority == null) {
            externalAuthority = directAuthority;
        }
        return context(
                socketIp,
                socketPort,
                true,
                forwardingHops,
                forwardedClientIp,
                externalScheme != null ? externalScheme : directScheme,
                externalAuthority,
                correlationId);
    }

    private ClientContext context(
            String socketIp,
            Integer socketPort,
            boolean trust,
            List<ForwardingHop> forwardingHops,
            String forwardedClientIp,
            String externalScheme,
            Authority externalAuthority,
            String correlationId) {
        return new ClientContext(
                socketIp,
                trust,
                forwardedClientIp,
                externalScheme,
                socketPort,
                forwardingHops,
                externalAuthority != null ? externalAuthority.host() : null,
                externalAuthority != null ? externalAuthority.port() : null,
                null,
                null,
                correlationId);
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

    /** Add identifiers only after the credential has been successfully verified. */
    public ClientContext attachVerifiedIdentity(
            RoutingContext routingContext, String principalId, String credentialId) {
        final var authenticated = getOrCompute(routingContext).withVerifiedIdentity(principalId, credentialId);
        routingContext.put(CONTEXT_PROPERTY, authenticated);
        return authenticated;
    }

    private List<String> extractForwardingChain(String forwarded, String xForwardedFor, String xRealIp) {
        if (forwarded != null && !forwarded.isBlank()) {
            return parseForwardedChain(forwarded);
        }

        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return parseXForwardedForChain(xForwardedFor);
        }

        if (xRealIp != null && !xRealIp.isBlank() && xRealIp.length() <= MAX_FORWARDING_HEADER_LENGTH) {
            final var address = normalizeIpLiteral(xRealIp.trim());
            return address == null ? List.of() : List.of(address);
        }

        return List.of();
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

    private String resolveRightmostUntrusted(List<ForwardingHop> chain) {
        for (var i = chain.size() - 1; i >= 0; i--) {
            final var hop = chain.get(i);
            if (!hop.trusted()) {
                return hop.ip();
            }
        }
        return chain.isEmpty() ? null : chain.getFirst().ip();
    }

    private Authority extractExternalAuthority(String forwarded, String xForwardedHost, String xForwardedPort) {
        final var authority = forwarded != null && !forwarded.isBlank()
                ? parseForwardedAuthority(forwarded)
                : parseXForwardedAuthority(xForwardedHost);
        if (authority == null || authority.port() != null || xForwardedPort == null) {
            return authority;
        }
        final var port = parseXForwardedPort(xForwardedPort);
        return port == null ? null : new Authority(authority.host(), port);
    }

    private Integer parseXForwardedPort(String header) {
        if (header.isBlank() || header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return null;
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return null;
        }
        Integer externalPort = null;
        for (final var entry : entries) {
            final var port = parsePort(entry.trim());
            if (port == null) {
                return null;
            }
            if (externalPort == null) {
                externalPort = port;
            }
        }
        return externalPort;
    }

    private Authority parseForwardedAuthority(String header) {
        if (header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return null;
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return null;
        }
        for (final var entry : entries) {
            Authority authority = null;
            for (final var part : entry.split(";", -1)) {
                final var trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "host=", 0, 5)) {
                    if (authority != null) {
                        return null;
                    }
                    authority = parseAuthority(trimmed.substring(5));
                    if (authority == null) {
                        return null;
                    }
                }
            }
            if (authority != null) {
                return authority;
            }
        }
        return null;
    }

    private Authority parseXForwardedAuthority(String header) {
        if (header == null || header.isBlank() || header.length() > MAX_FORWARDING_HEADER_LENGTH) {
            return null;
        }
        final var entries = header.split(",", -1);
        if (entries.length > MAX_FORWARDING_HOPS) {
            return null;
        }
        Authority external = null;
        for (final var entry : entries) {
            final var authority = parseAuthority(entry);
            if (authority == null) {
                return null;
            }
            if (external == null) {
                external = authority;
            }
        }
        return external;
    }

    private Authority parseAuthority(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        var value = unquote(rawValue.trim());
        if (value == null
                || value.isBlank()
                || value.length() > MAX_AUTHORITY_LENGTH
                || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c))
                || value.indexOf('@') >= 0
                || value.indexOf('/') >= 0) {
            return null;
        }

        String host;
        Integer port = null;
        if (value.startsWith("[")) {
            final var end = value.indexOf(']');
            if (end < 2) {
                return null;
            }
            host = normalizeIpLiteral(value.substring(1, end));
            final var suffix = value.substring(end + 1);
            if (!suffix.isEmpty()) {
                port = suffix.startsWith(":") ? parsePort(suffix.substring(1)) : null;
                if (port == null) {
                    return null;
                }
            }
        } else {
            final var colon = value.indexOf(':');
            if (colon >= 0) {
                if (colon != value.lastIndexOf(':')) {
                    return null;
                }
                port = parsePort(value.substring(colon + 1));
                if (port == null) {
                    return null;
                }
                value = value.substring(0, colon);
            }
            host = normalizeHost(value);
        }
        return host == null ? null : new Authority(host, port);
    }

    private String normalizeHost(String value) {
        final var ip = normalizeIpLiteral(value);
        if (ip != null) {
            return ip;
        }
        try {
            final var ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.length() > 253 || ascii.isBlank()) {
                return null;
            }
            for (final var label : ascii.split("\\.", -1)) {
                if (label.isBlank() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    return null;
                }
            }
            return ascii;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String unquote(String value) {
        if (!value.startsWith("\"") && !value.endsWith("\"")) {
            return value;
        }
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : null;
    }

    private Integer parsePort(String value) {
        if (!value.matches("\\d{1,5}")) {
            return null;
        }
        final var port = Integer.parseInt(value);
        return port > 0 && port <= 65535 ? port : null;
    }

    private String resolveCorrelationId(String value) {
        if (value != null && value.length() <= MAX_CORRELATION_ID_LENGTH && value.matches("[A-Za-z0-9._:-]+")) {
            return value;
        }
        return UUID.randomUUID().toString();
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
                .map(IpNetwork::canonicalAddress)
                .orElse(null);
    }

    private boolean isValidPort(String value) {
        return parsePort(value) != null;
    }

    private record Authority(String host, Integer port) {}
}
