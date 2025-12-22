package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.service.auth.AuthRateLimitService;
import aussie.spi.FailedAttemptRepository;

@DisplayName("AuthRateLimitFilter")
class AuthRateLimitFilterTest {

    private AuthRateLimitFilter filter;
    private AuthRateLimitService rateLimitService;
    private AuthRateLimitConfig config;
    private SecurityEventDispatcher securityEventDispatcher;
    private TelemetryHelper telemetryHelper;
    private FailedAttemptRepository failedAttemptRepository;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(AuthRateLimitService.class);
        config = mock(AuthRateLimitConfig.class);
        securityEventDispatcher = mock(SecurityEventDispatcher.class);
        telemetryHelper = mock(TelemetryHelper.class);
        failedAttemptRepository = mock(FailedAttemptRepository.class);
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);

        when(config.enabled()).thenReturn(true);
        when(config.includeHeaders()).thenReturn(true);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        filter = new AuthRateLimitFilter(
                rateLimitService, config, securityEventDispatcher, telemetryHelper, failedAttemptRepository);
    }

    private void setupRequestContext(String path, String forwarded, String xForwardedFor) {
        when(uriInfo.getPath()).thenReturn(path);
        when(requestContext.getHeaderString("Forwarded")).thenReturn(forwarded);
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(xForwardedFor);
        when(requestContext.getHeaderString("X-API-Key")).thenReturn(null);
        when(requestContext.getHeaderString("Authorization")).thenReturn(null);
    }

    @Nested
    @DisplayName("When rate limiting is disabled")
    class DisabledTests {

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void shouldSkipRateLimitingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(uriInfo.getPath()).thenReturn("/auth/login");

            var result = filter.filter(requestContext);

            assertNull(result.await().indefinitely());
            verify(rateLimitService, never()).checkAuthLimit(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Path filtering")
    class PathFilteringTests {

        @Test
        @DisplayName("should apply to /auth endpoints")
        void shouldApplyToAuthEndpoints() {
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNull(response);
            verify(rateLimitService).checkAuthLimit(anyString(), any());
        }

        @Test
        @DisplayName("should apply to /admin/sessions endpoints")
        void shouldApplyToAdminSessionsEndpoints() {
            setupRequestContext("/admin/sessions", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNull(response);
            verify(rateLimitService).checkAuthLimit(anyString(), any());
        }

        @Test
        @DisplayName("should skip non-auth endpoints")
        void shouldSkipNonAuthEndpoints() {
            setupRequestContext("/api/users", null, "192.168.1.1");

            var response = filter.filter(requestContext).await().indefinitely();

            assertNull(response);
            verify(rateLimitService, never()).checkAuthLimit(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Client IP extraction")
    class ClientIpExtractionTests {

        @Test
        @DisplayName("should prefer RFC 7239 Forwarded header over X-Forwarded-For")
        void shouldPreferRfc7239ForwardedHeader() {
            setupRequestContext("/auth/login", "for=203.0.113.195", "10.0.0.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("203.0.113.195", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with multiple directives")
        void shouldParseForwardedHeaderWithMultipleDirectives() {
            setupRequestContext("/auth/login", "for=192.0.2.60;proto=http;by=203.0.113.43", null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("192.0.2.60", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with IPv6 address")
        void shouldParseForwardedHeaderWithIPv6() {
            setupRequestContext("/auth/login", "for=\"[2001:db8:cafe::17]\"", null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("2001:db8:cafe::17", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with port")
        void shouldParseForwardedHeaderWithPort() {
            setupRequestContext("/auth/login", "for=192.0.2.60:8080", null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("192.0.2.60", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should extract first IP from RFC 7239 Forwarded header with multiple proxies")
        void shouldExtractFirstIpFromForwardedHeaderWithMultipleProxies() {
            setupRequestContext("/auth/login", "for=192.0.2.60, for=198.51.100.178", null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("192.0.2.60", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should fallback to X-Forwarded-For when Forwarded header has no for directive")
        void shouldFallbackToXForwardedForWhenNoForDirective() {
            setupRequestContext("/auth/login", "proto=https;by=203.0.113.43", "10.0.0.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("10.0.0.1", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should fallback to X-Forwarded-For when Forwarded header is absent")
        void shouldFallbackToXForwardedForWhenForwardedAbsent() {
            setupRequestContext("/auth/login", null, "10.0.0.1, 192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("10.0.0.1", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should handle case-insensitive for directive in Forwarded header")
        void shouldHandleCaseInsensitiveForDirective() {
            setupRequestContext("/auth/login", "FOR=192.0.2.60", null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("192.0.2.60", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should return 'unknown' when no IP headers present")
        void shouldReturnUnknownWhenNoIpHeaders() {
            setupRequestContext("/auth/login", null, null);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext).await().indefinitely();

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("unknown", ipCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Rate limit enforcement")
    class RateLimitEnforcementTests {

        @Test
        @DisplayName("should allow request when not rate limited")
        void shouldAllowRequestWhenNotRateLimited() {
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNull(response);
        }

        @Test
        @DisplayName("should return 429 when rate limited")
        void shouldReturn429WhenRateLimited() {
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.blocked(
                    "192.168.1.1", 60, Instant.now().plusSeconds(60));
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));
            when(failedAttemptRepository.getLockoutCount(anyString()))
                    .thenReturn(Uni.createFrom().item(1));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNotNull(response);
            assertEquals(429, response.getStatus());
            assertNotNull(response.getHeaderString("Retry-After"));
        }

        @Test
        @DisplayName("should include lockout headers when configured")
        void shouldIncludeLockoutHeadersWhenConfigured() {
            when(config.includeHeaders()).thenReturn(true);
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var lockoutExpiry = Instant.now().plusSeconds(60);
            var rateLimitResult = AuthRateLimitService.RateLimitResult.blocked("test-key", 60, lockoutExpiry);
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));
            when(failedAttemptRepository.getLockoutCount(anyString()))
                    .thenReturn(Uni.createFrom().item(1));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNotNull(response);
            assertEquals("test-key", response.getHeaderString("X-Auth-Lockout-Key"));
            assertNotNull(response.getHeaderString("X-Auth-Lockout-Reset"));
        }

        @Test
        @DisplayName("should not include lockout headers when not configured")
        void shouldNotIncludeLockoutHeadersWhenNotConfigured() {
            when(config.includeHeaders()).thenReturn(false);
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.blocked(
                    "test-key", 60, Instant.now().plusSeconds(60));
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));
            when(failedAttemptRepository.getLockoutCount(anyString()))
                    .thenReturn(Uni.createFrom().item(1));

            var response = filter.filter(requestContext).await().indefinitely();

            assertNotNull(response);
            assertNull(response.getHeaderString("X-Auth-Lockout-Key"));
        }
    }
}
