package aussie.adapter.in.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecurityHeadersFilter")
class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private Instance<SecurityHeadersConfig> configInstance;
    private SecurityHeadersConfig config;
    private RoutingContext ctx;
    private HttpServerResponse response;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        configInstance = mock(Instance.class);
        config = mock(SecurityHeadersConfig.class);
        ctx = mock(RoutingContext.class);
        response = mock(HttpServerResponse.class);

        when(ctx.response()).thenReturn(response);
        when(configInstance.isResolvable()).thenReturn(true);
        when(configInstance.get()).thenReturn(config);
        when(response.putHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(response);

        filter = new SecurityHeadersFilter(configInstance);
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
}
