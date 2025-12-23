package aussie.adapter.in.http;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.core.model.common.CorsConfig;

/**
 * CORS filter for gateway requests using Vert.x RouteFilter.
 *
 * <p>
 * Handle CORS preflight (OPTIONS) requests and adds CORS headers to
 * responses. Uses global CORS configuration from application properties.
 *
 * <p>
 * This filter runs at the Vert.x routing level (before JAX-RS) to ensure
 * all requests, including proxied ones, get proper CORS handling.
 */
@ApplicationScoped
public class CorsFilter {

    private static final Logger LOG = Logger.getLogger(CorsFilter.class);

    private static final String ORIGIN_HEADER = "Origin";
    private static final String ACCESS_CONTROL_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ACCESS_CONTROL_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String ACCESS_CONTROL_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String ACCESS_CONTROL_EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    private static final String ACCESS_CONTROL_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    private static final String ACCESS_CONTROL_MAX_AGE = "Access-Control-Max-Age";
    private static final String ACCESS_CONTROL_REQUEST_METHOD = "Access-Control-Request-Method";
    private static final String VARY = "Vary";

    private final Instance<GatewayCorsConfig> corsConfigInstance;

    @Inject
    public CorsFilter(Instance<GatewayCorsConfig> corsConfigInstance) {
        this.corsConfigInstance = corsConfigInstance;
    }

    /**
     * Route filter that handles CORS for all incoming requests.
     * Priority 100 ensures this runs early in the filter chain.
     */
    @RouteFilter(100)
    void corsHandler(RoutingContext rc) {
        // Skip if CORS is not configured
        if (!corsConfigInstance.isResolvable()) {
            LOG.debug("CORS config not resolvable, skipping");
            rc.next();
            return;
        }

        GatewayCorsConfig globalConfig = corsConfigInstance.get();
        if (!globalConfig.enabled()) {
            LOG.debug("CORS is disabled, skipping");
            rc.next();
            return;
        }

        String origin = rc.request().getHeader(ORIGIN_HEADER);
        if (origin == null || origin.isBlank()) {
            rc.next();
            return; // Not a CORS request
        }

        LOG.debugf(
                "CORS request: %s %s from origin %s",
                rc.request().method(), rc.request().path(), origin);

        // Build global CORS config
        CorsConfig corsConfig = buildGlobalCorsConfig(globalConfig);

        // Handle preflight (OPTIONS) requests
        if ("OPTIONS".equalsIgnoreCase(rc.request().method().name())) {
            LOG.debugf("Handling CORS preflight for origin %s", origin);
            handlePreflight(rc, origin, corsConfig);
            return; // Don't call next() - we're handling the response
        }

        // For non-preflight requests, add CORS headers and continue
        addCorsHeaders(rc, origin, corsConfig);
        rc.next();
    }

    private void handlePreflight(RoutingContext rc, String origin, CorsConfig corsConfig) {
        String requestMethod = rc.request().getHeader(ACCESS_CONTROL_REQUEST_METHOD);

        // Check if origin is allowed
        if (!corsConfig.isOriginAllowed(origin)) {
            LOG.debugf("CORS preflight rejected: origin %s not allowed", origin);
            rc.response().setStatusCode(403).end("Forbidden");
            return;
        }

        // Check if method is allowed
        if (requestMethod != null && !corsConfig.isMethodAllowed(requestMethod)) {
            LOG.debugf("CORS preflight rejected: method %s not allowed", requestMethod);
            rc.response().setStatusCode(403).end("Forbidden");
            return;
        }

        // Set allowed origin
        if (corsConfig.allowedOrigins().contains("*") && !corsConfig.allowCredentials()) {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            rc.response().putHeader(VARY, ORIGIN_HEADER);
        }

        // Set allowed methods
        rc.response().putHeader(ACCESS_CONTROL_ALLOW_METHODS, corsConfig.getAllowedMethodsString());

        // Set allowed headers
        rc.response().putHeader(ACCESS_CONTROL_ALLOW_HEADERS, corsConfig.getAllowedHeadersString());

        // Set credentials
        if (corsConfig.allowCredentials()) {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        // Set max age
        corsConfig.maxAge().ifPresent(maxAge -> rc.response().putHeader(ACCESS_CONTROL_MAX_AGE, maxAge.toString()));

        LOG.debugf("CORS preflight accepted for origin %s", origin);
        rc.response().setStatusCode(200).end();
    }

    private void addCorsHeaders(RoutingContext rc, String origin, CorsConfig corsConfig) {
        // Check if origin is allowed
        if (!corsConfig.isOriginAllowed(origin)) {
            return;
        }

        // Set allowed origin
        if (corsConfig.allowedOrigins().contains("*") && !corsConfig.allowCredentials()) {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        } else {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            rc.response().putHeader(VARY, ORIGIN_HEADER);
        }

        // Set credentials
        if (corsConfig.allowCredentials()) {
            rc.response().putHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }

        // Set exposed headers
        String exposedHeaders = corsConfig.getExposedHeadersString();
        if (exposedHeaders != null) {
            rc.response().putHeader(ACCESS_CONTROL_EXPOSE_HEADERS, exposedHeaders);
        }
    }

    private CorsConfig buildGlobalCorsConfig(GatewayCorsConfig globalConfig) {
        return new CorsConfig(
                globalConfig.allowedOrigins(),
                globalConfig.allowedMethods(),
                globalConfig.allowedHeaders(),
                globalConfig.exposedHeaders().orElse(Set.of()),
                globalConfig.allowCredentials(),
                globalConfig.maxAge());
    }
}
