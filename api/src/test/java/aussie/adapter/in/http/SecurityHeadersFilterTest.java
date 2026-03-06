package aussie.adapter.in.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.common.ServiceSecurityHeadersConfig;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("SecurityHeadersFilter")
class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private Instance<SecurityHeadersConfig> configInstance;
    private SecurityHeadersConfig config;
    private ServiceRegistry serviceRegistry;
    private RoutingContext ctx;
    private HttpServerRequest request;
    private HttpServerResponse response;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        configInstance = mock(Instance.class);
        config = mock(SecurityHeadersConfig.class);
        serviceRegistry = mock(ServiceRegistry.class);
        ctx = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);

        when(ctx.response()).thenReturn(response);
        when(ctx.request()).thenReturn(request);
        when(request.path()).thenReturn("/");
        when(configInstance.isResolvable()).thenReturn(true);
        when(configInstance.get()).thenReturn(config);
        when(response.putHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(response);
        when(serviceRegistry.getServiceFromLocalCache(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        filter = new SecurityHeadersFilter(configInstance, serviceRegistry);
    }

    private void enableDefaults() {
        when(config.enabled()).thenReturn(true);
        when(config.contentTypeOptions()).thenReturn("nosniff");
        when(config.frameOptions()).thenReturn("DENY");
        when(config.contentSecurityPolicy()).thenReturn("default-src 'none'");
        when(config.referrerPolicy()).thenReturn("strict-origin-when-cross-origin");
        when(config.permittedCrossDomainPolicies()).thenReturn("none");
        when(config.strictTransportSecurity()).thenReturn(Optional.empty());
        when(config.permissionsPolicy()).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("When config is not resolvable")
    class ConfigNotResolvable {

        @Test
        @DisplayName("should pass through without setting headers")
        void shouldPassThroughWithoutHeaders() {
            when(configInstance.isResolvable()).thenReturn(false);

            filter.addSecurityHeaders(ctx);

            verify(ctx).next();
            verify(response, never())
                    .putHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("When disabled")
    class Disabled {

        @Test
        @DisplayName("should pass through without setting headers")
        void shouldPassThroughWithoutHeaders() {
            when(config.enabled()).thenReturn(false);

            filter.addSecurityHeaders(ctx);

            verify(ctx).next();
            verify(response, never())
                    .putHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("When enabled with defaults")
    class EnabledWithDefaults {

        @BeforeEach
        void setUp() {
            enableDefaults();
        }

        @Test
        @DisplayName("should set X-Content-Type-Options")
        void shouldSetContentTypeOptions() {
            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Content-Type-Options", "nosniff");
        }

        @Test
        @DisplayName("should set X-Frame-Options")
        void shouldSetFrameOptions() {
            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Frame-Options", "DENY");
        }

        @Test
        @DisplayName("should set Content-Security-Policy")
        void shouldSetContentSecurityPolicy() {
            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Content-Security-Policy", "default-src 'none'");
        }

        @Test
        @DisplayName("should set Referrer-Policy")
        void shouldSetReferrerPolicy() {
            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        }

        @Test
        @DisplayName("should set X-Permitted-Cross-Domain-Policies")
        void shouldSetPermittedCrossDomainPolicies() {
            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Permitted-Cross-Domain-Policies", "none");
        }

        @Test
        @DisplayName("should not set Strict-Transport-Security when not configured")
        void shouldNotSetHstsWhenNotConfigured() {
            filter.addSecurityHeaders(ctx);

            verify(response, never())
                    .putHeader(
                            org.mockito.ArgumentMatchers.eq("Strict-Transport-Security"),
                            org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("should not set Permissions-Policy when not configured")
        void shouldNotSetPermissionsPolicyWhenNotConfigured() {
            filter.addSecurityHeaders(ctx);

            verify(response, never())
                    .putHeader(
                            org.mockito.ArgumentMatchers.eq("Permissions-Policy"),
                            org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("should call next() to continue filter chain")
        void shouldCallNext() {
            filter.addSecurityHeaders(ctx);

            verify(ctx).next();
        }
    }

    @Nested
    @DisplayName("When enabled with custom values")
    class EnabledWithCustomValues {

        @Test
        @DisplayName("should use configured values, not hardcoded defaults")
        void shouldUseConfiguredValues() {
            when(config.enabled()).thenReturn(true);
            when(config.contentTypeOptions()).thenReturn("nosniff");
            when(config.frameOptions()).thenReturn("SAMEORIGIN");
            when(config.contentSecurityPolicy()).thenReturn("default-src 'self'");
            when(config.referrerPolicy()).thenReturn("no-referrer");
            when(config.permittedCrossDomainPolicies()).thenReturn("master-only");
            when(config.strictTransportSecurity()).thenReturn(Optional.empty());
            when(config.permissionsPolicy()).thenReturn(Optional.empty());

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Frame-Options", "SAMEORIGIN");
            verify(response).putHeader("Content-Security-Policy", "default-src 'self'");
            verify(response).putHeader("Referrer-Policy", "no-referrer");
            verify(response).putHeader("X-Permitted-Cross-Domain-Policies", "master-only");
        }
    }

    @Nested
    @DisplayName("Optional headers")
    class OptionalHeaders {

        @BeforeEach
        void setUp() {
            enableDefaults();
        }

        @Test
        @DisplayName("should set HSTS when configured")
        void shouldSetHstsWhenConfigured() {
            when(config.strictTransportSecurity()).thenReturn(Optional.of("max-age=31536000; includeSubDomains"));

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }

        @Test
        @DisplayName("should set Permissions-Policy when configured")
        void shouldSetPermissionsPolicyWhenConfigured() {
            when(config.permissionsPolicy()).thenReturn(Optional.of("camera=(), microphone=(), geolocation=()"));

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        }
    }

    @Nested
    @DisplayName("Per-service overrides")
    class PerServiceOverrides {

        @BeforeEach
        void setUp() {
            enableDefaults();
            when(request.path()).thenReturn("/dashboard-service/api/page");
        }

        private void mockServiceWithHeaders(ServiceSecurityHeadersConfig headersConfig) {
            final var service = ServiceRegistration.builder("dashboard-service")
                    .baseUrl("http://localhost:9090")
                    .securityHeadersConfig(headersConfig)
                    .build();
            when(serviceRegistry.getServiceFromLocalCache("dashboard-service")).thenReturn(Optional.of(service));
        }

        @Test
        @DisplayName("should override CSP for service")
        void shouldOverrideCspForService() {
            final var override = ServiceSecurityHeadersConfig.builder()
                    .contentSecurityPolicy("default-src 'self'; script-src 'self' 'unsafe-inline'")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response)
                    .putHeader("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'");
            // Other headers should still use global defaults
            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Frame-Options", "DENY");
            verify(response).putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            verify(response).putHeader("X-Permitted-Cross-Domain-Policies", "none");
        }

        @Test
        @DisplayName("should override multiple headers for service")
        void shouldOverrideMultipleHeaders() {
            final var override = ServiceSecurityHeadersConfig.builder()
                    .contentSecurityPolicy("default-src 'self'")
                    .frameOptions("SAMEORIGIN")
                    .referrerPolicy("no-referrer")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Content-Security-Policy", "default-src 'self'");
            verify(response).putHeader("X-Frame-Options", "SAMEORIGIN");
            verify(response).putHeader("Referrer-Policy", "no-referrer");
            // Unoverridden headers use global defaults
            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Permitted-Cross-Domain-Policies", "none");
        }

        @Test
        @DisplayName("should suppress header when override is empty string")
        void shouldSuppressHeaderWithEmptyStringOverride() {
            final var override = ServiceSecurityHeadersConfig.builder()
                    .contentSecurityPolicy("")
                    .frameOptions("")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response, never())
                    .putHeader(
                            org.mockito.ArgumentMatchers.eq("Content-Security-Policy"),
                            org.mockito.ArgumentMatchers.anyString());
            verify(response, never())
                    .putHeader(
                            org.mockito.ArgumentMatchers.eq("X-Frame-Options"),
                            org.mockito.ArgumentMatchers.anyString());
            // Other headers still applied
            verify(response).putHeader("X-Content-Type-Options", "nosniff");
        }

        @Test
        @DisplayName("should apply custom headers from service config")
        void shouldApplyCustomHeaders() {
            final var override = ServiceSecurityHeadersConfig.builder()
                    .customHeaders(Map.of(
                            "X-Custom-Header", "custom-value",
                            "X-Another-Header", "another-value"))
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Custom-Header", "custom-value");
            verify(response).putHeader("X-Another-Header", "another-value");
            // Global defaults still applied
            verify(response).putHeader("X-Content-Type-Options", "nosniff");
        }

        @Test
        @DisplayName("should override optional HSTS header for service")
        void shouldOverrideHstsForService() {
            when(config.strictTransportSecurity()).thenReturn(Optional.of("max-age=31536000"));

            final var override = ServiceSecurityHeadersConfig.builder()
                    .strictTransportSecurity("max-age=86400")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Strict-Transport-Security", "max-age=86400");
        }

        @Test
        @DisplayName("should suppress optional HSTS header with empty string")
        void shouldSuppressHstsWithEmptyString() {
            when(config.strictTransportSecurity()).thenReturn(Optional.of("max-age=31536000"));

            final var override = ServiceSecurityHeadersConfig.builder()
                    .strictTransportSecurity("")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response, never())
                    .putHeader(
                            org.mockito.ArgumentMatchers.eq("Strict-Transport-Security"),
                            org.mockito.ArgumentMatchers.anyString());
        }

        @Test
        @DisplayName("should add Permissions-Policy via service override when not set globally")
        void shouldAddPermissionsPolicyViaOverride() {
            final var override = ServiceSecurityHeadersConfig.builder()
                    .permissionsPolicy("camera=(), microphone=()")
                    .build();
            mockServiceWithHeaders(override);

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("Permissions-Policy", "camera=(), microphone=()");
        }
    }

    @Nested
    @DisplayName("Service resolution edge cases")
    class ServiceResolutionEdgeCases {

        @BeforeEach
        void setUp() {
            enableDefaults();
        }

        @Test
        @DisplayName("should use global defaults when path has no service ID")
        void shouldUseGlobalDefaultsForRootPath() {
            when(request.path()).thenReturn("/");

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Frame-Options", "DENY");
        }

        @Test
        @DisplayName("should use global defaults when service has no security headers config")
        void shouldUseGlobalDefaultsWhenNoServiceConfig() {
            when(request.path()).thenReturn("/my-service/api/data");
            final var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:9090")
                    .build();
            when(serviceRegistry.getServiceFromLocalCache("my-service")).thenReturn(Optional.of(service));

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Frame-Options", "DENY");
            verify(response).putHeader("Content-Security-Policy", "default-src 'none'");
        }

        @Test
        @DisplayName("should use global defaults when service is not in local cache")
        void shouldUseGlobalDefaultsWhenServiceNotInCache() {
            when(request.path()).thenReturn("/unknown-service/api/data");
            when(serviceRegistry.getServiceFromLocalCache("unknown-service")).thenReturn(Optional.empty());

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Frame-Options", "DENY");
        }

        @Test
        @DisplayName("should use global defaults when path is null")
        void shouldUseGlobalDefaultsForNullPath() {
            when(request.path()).thenReturn(null);

            filter.addSecurityHeaders(ctx);

            verify(response).putHeader("X-Content-Type-Options", "nosniff");
            verify(response).putHeader("X-Frame-Options", "DENY");
        }
    }
}
