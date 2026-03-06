package aussie.adapter.in.http;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.core.model.common.ServiceSecurityHeadersConfig;
import aussie.core.model.service.ServicePath;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Security headers filter for all gateway responses using Vert.x RouteFilter.
 *
 * <p>Adds standard security response headers to all responses following
 * OWASP recommendations. This filter runs at the Vert.x routing level
 * (before JAX-RS) to ensure all responses, including proxied ones and
 * error pages, include security headers.
 *
 * <p>Per-service overrides are supported via {@link ServiceSecurityHeadersConfig}
 * on the service registration. When a service declares header overrides, those
 * values replace the global defaults. An empty string override suppresses the
 * header entirely for that service.
 *
 * <p>Priority 90 ensures this runs after CORS (100) but before other filters.
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    private static final Logger LOG = Logger.getLogger(SecurityHeadersFilter.class);

    private final Instance<SecurityHeadersConfig> configInstance;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public SecurityHeadersFilter(Instance<SecurityHeadersConfig> configInstance, ServiceRegistry serviceRegistry) {
        this.configInstance = configInstance;
        this.serviceRegistry = serviceRegistry;
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
        final var serviceOverride = resolveServiceOverride(rc.request().path());

        applyHeader(
                response,
                "X-Content-Type-Options",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::contentTypeOptions),
                config.contentTypeOptions());
        applyHeader(
                response,
                "X-Frame-Options",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::frameOptions),
                config.frameOptions());
        applyHeader(
                response,
                "Content-Security-Policy",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::contentSecurityPolicy),
                config.contentSecurityPolicy());
        applyHeader(
                response,
                "Referrer-Policy",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::referrerPolicy),
                config.referrerPolicy());
        applyHeader(
                response,
                "X-Permitted-Cross-Domain-Policies",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::permittedCrossDomainPolicies),
                config.permittedCrossDomainPolicies());

        applyOptionalHeader(
                response,
                "Strict-Transport-Security",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::strictTransportSecurity),
                config.strictTransportSecurity());
        applyOptionalHeader(
                response,
                "Permissions-Policy",
                serviceOverride.flatMap(ServiceSecurityHeadersConfig::permissionsPolicy),
                config.permissionsPolicy());

        serviceOverride.ifPresent(override -> override.customHeaders().forEach(response::putHeader));

        rc.next();
    }

    private Optional<ServiceSecurityHeadersConfig> resolveServiceOverride(String path) {
        final var servicePath = ServicePath.parse(path);
        return serviceRegistry
                .getServiceFromLocalCache(servicePath.serviceId())
                .flatMap(ServiceRegistration::securityHeadersConfig);
    }

    private void applyHeader(HttpServerResponse response, String name, Optional<String> override, String defaultValue) {
        final var value = override.orElse(defaultValue);
        if (!value.isEmpty()) {
            response.putHeader(name, value);
        }
    }

    private void applyOptionalHeader(
            HttpServerResponse response, String name, Optional<String> override, Optional<String> defaultValue) {
        final var effective = override.isPresent() ? override : defaultValue;
        effective.ifPresent(v -> {
            if (!v.isEmpty()) {
                response.putHeader(name, v);
            }
        });
    }
}
