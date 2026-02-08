package aussie.adapter.in.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * Security headers filter for all gateway responses using Vert.x RouteFilter.
 *
 * <p>Adds standard security response headers to all responses following
 * OWASP recommendations. This filter runs at the Vert.x routing level
 * (before JAX-RS) to ensure all responses, including proxied ones and
 * error pages, include security headers.
 *
 * <p>Priority 90 ensures this runs after CORS (100) but before other filters.
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    private static final Logger LOG = Logger.getLogger(SecurityHeadersFilter.class);

    private final Instance<SecurityHeadersConfig> configInstance;

    @Inject
    public SecurityHeadersFilter(Instance<SecurityHeadersConfig> configInstance) {
        this.configInstance = configInstance;
    }

    /**
     * Route filter that adds security headers to all responses.
     * Priority 90 runs after CORS (100) to ensure CORS headers are
     * not overwritten, but before application logic.
     */
    @RouteFilter(90)
    void addSecurityHeaders(RoutingContext rc) {
        if (!configInstance.isResolvable()) {
            LOG.debug("Security headers config not resolvable, skipping");
            rc.next();
            return;
        }

        final var config = configInstance.get();
        if (!config.enabled()) {
            rc.next();
            return;
        }

        final var response = rc.response();

        response.putHeader("X-Content-Type-Options", config.contentTypeOptions());
        response.putHeader("X-Frame-Options", config.frameOptions());
        response.putHeader("Content-Security-Policy", config.contentSecurityPolicy());
        response.putHeader("Referrer-Policy", config.referrerPolicy());
        response.putHeader("X-Permitted-Cross-Domain-Policies", config.permittedCrossDomainPolicies());

        config.strictTransportSecurity().ifPresent(v -> response.putHeader("Strict-Transport-Security", v));
        config.permissionsPolicy().ifPresent(v -> response.putHeader("Permissions-Policy", v));

        rc.next();
    }
}
