package aussie.system.filter;

import java.net.URI;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.http.GatewayCorsConfig;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.common.context.PlatformPaths;
import aussie.core.config.SessionConfig;

/** Rejects untrusted cross-origin mutations made with a browser session cookie. */
@ApplicationScoped
public class CsrfProtectionFilter {

    private static final String ORIGIN = "Origin";

    private final SessionConfig sessionConfig;
    private final SessionCookieManager cookieManager;
    private final GatewayCorsConfig corsConfig;
    private final ClientContextResolver clientContextResolver;
    private final RoutingContext routingContext;

    @Inject
    public CsrfProtectionFilter(
            SessionConfig sessionConfig,
            SessionCookieManager cookieManager,
            GatewayCorsConfig corsConfig,
            ClientContextResolver clientContextResolver,
            RoutingContext routingContext) {
        this.sessionConfig = sessionConfig;
        this.cookieManager = cookieManager;
        this.corsConfig = corsConfig;
        this.clientContextResolver = clientContextResolver;
        this.routingContext = routingContext;
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION + 10)
    public void filter(ContainerRequestContext context, HttpServerRequest request) {
        if (!sessionConfig.enabled()
                || isSafeMethod(request.method().name())
                || (sessionConfig.publicCreationEnabled()
                        && PlatformPaths.isPublicSessionCreation(
                                request.path(), request.method().name()))
                || !cookieManager.hasSessionCookie(request)) {
            return;
        }

        final var origin = context.getHeaderString(ORIGIN);
        final var csrfCookie = request.getCookie(cookieManager.csrfCookieName());
        final var csrfHeader = context.getHeaderString(SessionCookieManager.CSRF_HEADER);
        if (!allowedOrigin(origin)
                || csrfCookie == null
                || csrfHeader == null
                || csrfHeader.isBlank()
                || !csrfHeader.equals(csrfCookie.getValue())) {
            throw GatewayProblem.forbidden("CSRF validation failed");
        }
    }

    private boolean allowedOrigin(String origin) {
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin.trim())) {
            return false;
        }
        try {
            final var normalizedOrigin = origin.trim();
            final var actual = URI.create(normalizedOrigin);
            if (actual.getScheme() == null
                    || actual.getHost() == null
                    || actual.getUserInfo() != null
                    || !actual.getPath().isEmpty()
                    || actual.getQuery() != null
                    || actual.getFragment() != null) {
                return false;
            }
            final var clientContext = clientContextResolver.getOrCompute(routingContext);
            final var sameOrigin = actual.getScheme().equalsIgnoreCase(clientContext.externalScheme())
                    && actual.getHost().equalsIgnoreCase(clientContext.externalHost())
                    && normalizedPort(actual)
                            == normalizedPort(clientContext.externalScheme(), clientContext.externalPort());
            return sameOrigin
                    || (corsConfig.enabled()
                            && corsConfig.allowCredentials()
                            && corsConfig.allowedOrigins().contains(normalizedOrigin));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int normalizedPort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private int normalizedPort(String scheme, Integer port) {
        if (port != null) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean isSafeMethod(String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }
}
