package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.service.auth.AuthRateLimitService;
import aussie.core.service.common.TrustedProxyValidator;
import aussie.spi.FailedAttemptRepository;

@DisplayName("AuthRateLimitFilter")
class AuthRateLimitFilterTest {

    private AuthRateLimitFilter filter;
    private AuthRateLimitService rateLimitService;
    private AuthRateLimitConfig config;
    private SecurityEventDispatcher securityEventDispatcher;
    private TelemetryHelper telemetryHelper;
    private FailedAttemptRepository failedAttemptRepository;
    private TrustedProxyValidator trustedProxyValidator;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;
    private HttpServerRequest vertxRequest;
    private SocketAddress socketAddress;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(AuthRateLimitService.class);
        config = mock(AuthRateLimitConfig.class);
        securityEventDispatcher = mock(SecurityEventDispatcher.class);
        telemetryHelper = mock(TelemetryHelper.class);
        failedAttemptRepository = mock(FailedAttemptRepository.class);
        trustedProxyValidator = mock(TrustedProxyValidator.class);
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        vertxRequest = mock(HttpServerRequest.class);
        socketAddress = mock(SocketAddress.class);

        when(config.enabled()).thenReturn(true);
        when(config.includeHeaders()).thenReturn(true);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);

        // Default: requests arrive from a trusted proxy at 127.0.0.1
        when(socketAddress.host()).thenReturn("127.0.0.1");
        when(vertxRequest.remoteAddress()).thenReturn(socketAddress);
        when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString())).thenReturn(true);

        filter = new AuthRateLimitFilter(
                rateLimitService,
                config,
                securityEventDispatcher,
                telemetryHelper,
                failedAttemptRepository,
                trustedProxyValidator);
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

            var result = filter.filter(requestContext, vertxRequest);

            assertNull(result.await().atMost(Duration.ofSeconds(5)));
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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            assertNull(response);
            verify(rateLimitService).checkAuthLimit(anyString(), any());
        }

        @Test
        @DisplayName("should skip non-auth endpoints")
        void shouldSkipNonAuthEndpoints() {
            setupRequestContext("/api/users", null, "192.168.1.1");

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            assertNull(response);
            verify(rateLimitService, never()).checkAuthLimit(anyString(), any());
        }

        @Test
        @DisplayName("should apply to /admin/api-keys endpoints")
        void shouldApplyToAdminApiKeysEndpoints() {
            setupRequestContext("/admin/api-keys", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            assertNull(response);
            verify(rateLimitService).checkAuthLimit(anyString(), any());
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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("192.0.2.60", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should return 'unknown' when no IP headers present and no remote address")
        void shouldReturnUnknownWhenNoIpHeaders() {
            setupRequestContext("/auth/login", null, null);
            when(vertxRequest.remoteAddress()).thenReturn(null);
            when(trustedProxyValidator.shouldTrustForwardingHeaders(null)).thenReturn(false);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("unknown", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should use socket IP as fallback when trusted proxy sends no forwarding headers")
        void shouldUseSocketIpWhenTrustedProxyAndNoForwardingHeaders() {
            setupRequestContext("/auth/login", null, null);
            when(socketAddress.host()).thenReturn("10.0.0.5");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("10.0.0.5", ipCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Trusted proxy validation")
    class TrustedProxyValidationTests {

        @Test
        @DisplayName("should ignore forwarding headers and use socket IP when proxy is not trusted")
        void shouldUseSocketIpWhenProxyNotTrusted() {
            setupRequestContext("/auth/login", "for=1.2.3.4", "1.2.3.4");
            when(socketAddress.host()).thenReturn("203.0.113.99");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("203.0.113.99"))
                    .thenReturn(false);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("203.0.113.99", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should honor forwarding headers when proxy is trusted")
        void shouldHonorForwardingHeadersWhenProxyIsTrusted() {
            setupRequestContext("/auth/login", "for=198.51.100.42", null);
            when(socketAddress.host()).thenReturn("10.10.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.10.0.1"))
                    .thenReturn(true);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("198.51.100.42", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should consult validator with the socket IP, not the header IP")
        void shouldConsultValidatorWithSocketIp() {
            final var proxySocketIp = "10.0.0.2";
            setupRequestContext("/auth/login", "for=198.51.100.55", null);
            when(socketAddress.host()).thenReturn(proxySocketIp);
            when(trustedProxyValidator.shouldTrustForwardingHeaders(proxySocketIp))
                    .thenReturn(true);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            verify(trustedProxyValidator).shouldTrustForwardingHeaders(proxySocketIp);
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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

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

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            assertNotNull(response);
            assertNull(response.getHeaderString("X-Auth-Lockout-Key"));
        }

        @Test
        @DisplayName("should handle null lockoutExpiry by computing from retryAfterSeconds")
        void shouldHandleNullLockoutExpiry() {
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.blocked("test-key", 60, null);
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));
            when(failedAttemptRepository.getLockoutCount(anyString()))
                    .thenReturn(Uni.createFrom().item(1));

            var response = filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            assertNotNull(response);
            assertEquals(429, response.getStatus());
            assertNotNull(response.getHeaderString("Retry-After"));
        }
    }

    @Nested
    @DisplayName("Identifier extraction")
    class IdentifierExtractionTests {

        @Test
        @DisplayName("should extract API key prefix when key is long enough")
        void shouldExtractApiKeyPrefix() {
            setupRequestContext("/auth/login", null, "192.168.1.1");
            when(requestContext.getHeaderString("X-API-Key")).thenReturn("abcdefgh12345678");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertEquals("abcdefgh", idCaptor.getValue());
        }

        @Test
        @DisplayName("should skip short API key and try Bearer token")
        void shouldSkipShortApiKey() {
            setupRequestContext("/auth/login", null, "192.168.1.1");
            when(requestContext.getHeaderString("X-API-Key")).thenReturn("short");
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer longtokenvalue123");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertEquals("longtoke", idCaptor.getValue());
        }

        @Test
        @DisplayName("should extract Bearer token prefix when token is long enough")
        void shouldExtractBearerTokenPrefix() {
            setupRequestContext("/auth/login", null, "192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer mytoken12345");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertEquals("mytoken1", idCaptor.getValue());
        }

        @Test
        @DisplayName("should skip short Bearer token")
        void shouldSkipShortBearerToken() {
            setupRequestContext("/auth/login", null, "192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer short");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertNull(idCaptor.getValue());
        }

        @Test
        @DisplayName("should skip non-Bearer Authorization header")
        void shouldSkipNonBearerAuth() {
            setupRequestContext("/auth/login", null, "192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertNull(idCaptor.getValue());
        }

        @Test
        @DisplayName("should return null identifier when no headers present")
        void shouldReturnNullIdentifierWhenNoHeaders() {
            setupRequestContext("/auth/login", null, "192.168.1.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(anyString(), idCaptor.capture());
            assertNull(idCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Client IP edge cases")
    class ClientIpEdgeCaseTests {

        @Test
        @DisplayName("should fallback to socketIp when trusted proxy and no forwarding headers")
        void shouldFallbackToSocketIpWhenNoForwardingHeaders() {
            setupRequestContext("/auth/login", null, null);
            when(socketAddress.host()).thenReturn("10.0.0.99");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("10.0.0.99", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should return 'unknown' when trusted proxy with null socketIp and no forwarding headers")
        void shouldReturnUnknownWhenTrustedProxyWithNullSocketIp() {
            setupRequestContext("/auth/login", null, null);
            when(vertxRequest.remoteAddress()).thenReturn(null);
            when(trustedProxyValidator.shouldTrustForwardingHeaders(null)).thenReturn(true);

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertEquals("unknown", ipCaptor.getValue());
        }

        @Test
        @DisplayName("should handle Forwarded header with malformed quotes")
        void shouldHandleForwardedHeaderWithMalformedQuotes() {
            setupRequestContext("/auth/login", "for=\"192.0.2.60", null);
            when(socketAddress.host()).thenReturn("10.0.0.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            // Malformed quote — value is still returned but with the quote
            assertNotNull(ipCaptor.getValue());
        }

        @Test
        @DisplayName("should handle IPv6 Forwarded with missing close bracket")
        void shouldHandleIPv6ForwardedWithMissingCloseBracket() {
            setupRequestContext("/auth/login", "for=[2001:db8:cafe::17", null);
            when(socketAddress.host()).thenReturn("10.0.0.1");

            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(rateLimitService.checkAuthLimit(anyString(), any()))
                    .thenReturn(Uni.createFrom().item(rateLimitResult));

            filter.filter(requestContext, vertxRequest).await().atMost(Duration.ofSeconds(5));

            ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
            verify(rateLimitService).checkAuthLimit(ipCaptor.capture(), any());
            assertNotNull(ipCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Static helper methods")
    class StaticHelperTests {

        @Test
        @DisplayName("getRateLimitResult should return empty when no property set")
        void getRateLimitResultShouldReturnEmptyWhenNoProperty() {
            when(requestContext.getProperty("aussie.auth.ratelimit.result")).thenReturn(null);

            var result = AuthRateLimitFilter.getRateLimitResult(requestContext);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getRateLimitResult should return result when property is set")
        void getRateLimitResultShouldReturnResultWhenPropertySet() {
            var rateLimitResult = AuthRateLimitService.RateLimitResult.allow();
            when(requestContext.getProperty("aussie.auth.ratelimit.result")).thenReturn(rateLimitResult);

            var result = AuthRateLimitFilter.getRateLimitResult(requestContext);

            assertTrue(result.isPresent());
            assertTrue(result.get().allowed());
        }
    }
}
