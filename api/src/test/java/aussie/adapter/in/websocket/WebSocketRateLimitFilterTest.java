package aussie.adapter.in.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.RateLimitResolver;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("WebSocketRateLimitFilter")
@ExtendWith(MockitoExtension.class)
class WebSocketRateLimitFilterTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private RateLimitingConfig config;

    @Mock
    private RateLimitResolver rateLimitResolver;

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private SecurityEventDispatcher securityEventDispatcher;

    @Mock
    private RoutingContext ctx;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    @Mock
    private RateLimitingConfig.WebSocketRateLimitConfig websocketConfig;

    @Mock
    private RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig connectionConfig;

    private WebSocketRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new WebSocketRateLimitFilter(
                rateLimiter, config, rateLimitResolver, serviceRegistry, metrics, securityEventDispatcher);

        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
    }

    private void mockWebSocketUpgrade() {
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(request.getHeader("Connection")).thenReturn("Upgrade");
    }

    private void mockRateLimitingEnabled() {
        when(config.enabled()).thenReturn(true);
        when(rateLimiter.isEnabled()).thenReturn(true);
        when(config.websocket()).thenReturn(websocketConfig);
        when(websocketConfig.connection()).thenReturn(connectionConfig);
        when(connectionConfig.enabled()).thenReturn(true);
    }

    private void mockDefaultClientId() {
        lenient().when(request.getCookie("aussie_session")).thenReturn(null);
        lenient().when(request.getHeader("X-Session-ID")).thenReturn(null);
        lenient().when(request.getHeader("Authorization")).thenReturn(null);
        lenient().when(request.getHeader("X-API-Key-ID")).thenReturn(null);
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, "127.0.0.1"));
    }

    private void mockAllowedRateLimit(String serviceId) {
        when(serviceRegistry.getServiceForRateLimiting(serviceId))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        lenient()
                .when(rateLimitResolver.resolveWebSocketConnectionLimit(any()))
                .thenReturn(EffectiveRateLimit.of(10, 60));
        lenient()
                .when(rateLimiter.checkAndConsume(any(), any()))
                .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));
    }

    @Nested
    @DisplayName("checkWebSocketRateLimit - bypass conditions")
    class BypassConditionTests {

        @Test
        @DisplayName("Should pass through non-WebSocket requests")
        void shouldPassThroughNonWebSocketRequests() {
            when(request.getHeader("Upgrade")).thenReturn(null);
            lenient().when(request.getHeader("Connection")).thenReturn(null);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }

        @Test
        @DisplayName("Should pass through when rate limiting is disabled globally")
        void shouldPassThroughWhenDisabled() {
            mockWebSocketUpgrade();
            when(config.enabled()).thenReturn(false);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }

        @Test
        @DisplayName("Should pass through when rateLimiter is disabled")
        void shouldPassThroughWhenRateLimiterDisabled() {
            mockWebSocketUpgrade();
            when(config.enabled()).thenReturn(true);
            when(rateLimiter.isEnabled()).thenReturn(false);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should pass through when websocket connection config is disabled")
        void shouldPassThroughWhenWsConnectionDisabled() {
            mockWebSocketUpgrade();
            when(config.enabled()).thenReturn(true);
            when(rateLimiter.isEnabled()).thenReturn(true);
            when(config.websocket()).thenReturn(websocketConfig);
            when(websocketConfig.connection()).thenReturn(connectionConfig);
            when(connectionConfig.enabled()).thenReturn(false);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/admin/dashboard", "/q/health", "/Admin/config", "/Q/metrics"})
        @DisplayName("Should pass through reserved paths")
        void shouldPassThroughReservedPaths(String path) {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn(path);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
            verify(serviceRegistry, never()).getServiceForRateLimiting(anyString());
        }
    }

    @Nested
    @DisplayName("isWebSocketUpgrade")
    class IsWebSocketUpgradeTests {

        @Test
        @DisplayName("Should detect standard WebSocket upgrade headers")
        void shouldDetectStandardHeaders() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();
            mockAllowedRateLimit("my-service");

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting("my-service");
        }

        @Test
        @DisplayName("Should not detect when Upgrade header is missing")
        void shouldNotDetectMissingUpgradeHeader() {
            when(request.getHeader("Upgrade")).thenReturn(null);
            lenient().when(request.getHeader("Connection")).thenReturn("Upgrade");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should not detect when Connection header is missing")
        void shouldNotDetectMissingConnectionHeader() {
            when(request.getHeader("Upgrade")).thenReturn("websocket");
            when(request.getHeader("Connection")).thenReturn(null);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should not detect when Connection header does not contain upgrade")
        void shouldNotDetectWrongConnectionHeader() {
            when(request.getHeader("Upgrade")).thenReturn("websocket");
            when(request.getHeader("Connection")).thenReturn("keep-alive");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should handle case-insensitive Upgrade header value")
        void shouldHandleCaseInsensitiveUpgrade() {
            when(request.getHeader("Upgrade")).thenReturn("WebSocket");
            when(request.getHeader("Connection")).thenReturn("upgrade, keep-alive");
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();
            mockAllowedRateLimit("my-service");

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting("my-service");
        }
    }

    @Nested
    @DisplayName("isReservedPath")
    class IsReservedPathTests {

        @ParameterizedTest
        @ValueSource(strings = {"/admin", "/admin/config", "/q", "/q/health/live"})
        @DisplayName("Should identify reserved paths")
        void shouldIdentifyReservedPaths(String path) {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn(path);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/my-service/ws", "/gateway/ws", "/api/v1"})
        @DisplayName("Should not identify non-reserved paths as reserved")
        void shouldNotIdentifyNonReservedPaths(String path) {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn(path);
            mockDefaultClientId();
            when(serviceRegistry.getServiceForRateLimiting(anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(rateLimitResolver.resolveWebSocketConnectionLimit(any())).thenReturn(EffectiveRateLimit.of(10, 60));
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting(anyString());
        }
    }

    @Nested
    @DisplayName("extractServiceId")
    class ExtractServiceIdTests {

        @Test
        @DisplayName("Should extract 'gateway' for /gateway/ paths")
        void shouldExtractGatewayServiceId() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/gateway/ws/echo");
            mockDefaultClientId();
            mockAllowedRateLimit("gateway");

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting("gateway");
        }

        @Test
        @DisplayName("Should extract service ID from pass-through path")
        void shouldExtractPassThroughServiceId() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws/echo");
            mockDefaultClientId();
            mockAllowedRateLimit("my-service");

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting("my-service");
        }

        @Test
        @DisplayName("Should extract service ID when no trailing path")
        void shouldExtractServiceIdWithoutTrailingPath() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service");
            mockDefaultClientId();
            mockAllowedRateLimit("my-service");

            filter.checkWebSocketRateLimit(ctx);

            verify(serviceRegistry).getServiceForRateLimiting("my-service");
        }
    }

    @Nested
    @DisplayName("extractClientId")
    class ExtractClientIdTests {

        private void setupRateLimitPath() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            when(serviceRegistry.getServiceForRateLimiting(anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            lenient()
                    .when(rateLimitResolver.resolveWebSocketConnectionLimit(any()))
                    .thenReturn(EffectiveRateLimit.of(10, 60));
            lenient()
                    .when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));
        }

        @Test
        @DisplayName("Should use session cookie when present")
        void shouldUseSessionCookie() {
            setupRateLimitPath();
            final var cookie = mock(Cookie.class);
            when(cookie.getValue()).thenReturn("session-abc");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should use session header when no cookie")
        void shouldUseSessionHeader() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn("session-header-123");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should use bearer auth hash when no session")
        void shouldUseBearerAuthHash() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn("Bearer my-token");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should use API key ID when no session or bearer")
        void shouldUseApiKeyId() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn("key-123");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should use X-Forwarded-For when no other identifiers")
        void shouldUseForwardedFor() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 192.168.1.1");

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should use remote address IP as last resort")
        void shouldUseRemoteAddress() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, "10.0.0.5"));

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should handle null remote address gracefully")
        void shouldHandleNullRemoteAddress() {
            setupRateLimitPath();
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.remoteAddress()).thenReturn(null);

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }
    }

    @Nested
    @DisplayName("Rate limit allowed")
    class RateLimitAllowedTests {

        @Test
        @DisplayName("Should pass through and record metrics when rate limit is allowed")
        void shouldPassThroughWhenAllowed() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();

            final var allowedDecision =
                    RateLimitDecision.allow(50, 100, 60, Instant.now().plusSeconds(60), 50, null);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(rateLimitResolver.resolveWebSocketConnectionLimit(any())).thenReturn(EffectiveRateLimit.of(100, 60));
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(allowedDecision));

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
            verify(metrics).recordRateLimitCheck(eq("my-service"), eq(true), eq(50L));
            verify(metrics, never()).recordRateLimitExceeded(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Rate limit exceeded")
    class RateLimitExceededTests {

        @Test
        @DisplayName("Should return 429 with headers when rate limit is exceeded")
        void shouldReturn429WhenExceeded() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();

            final var resetAt = Instant.now().plusSeconds(30);
            final var rejectedDecision = RateLimitDecision.rejected(100, 60, resetAt, 30, 101, null);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(rateLimitResolver.resolveWebSocketConnectionLimit(any())).thenReturn(EffectiveRateLimit.of(100, 60));
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(rejectedDecision));

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx, never()).next();
            verify(response).setStatusCode(429);
            verify(response).putHeader("Retry-After", "30");
            verify(response).putHeader("X-RateLimit-Limit", "100");
            verify(response).putHeader("X-RateLimit-Remaining", "0");
            verify(response).putHeader("X-RateLimit-Reset", String.valueOf(resetAt.getEpochSecond()));
            verify(response).end("Rate limit exceeded. Retry after 30 seconds.");
            verify(metrics).recordRateLimitExceeded("my-service", "ws_connection");
            verify(securityEventDispatcher).dispatch(any());
        }

        @Test
        @DisplayName("Should record metrics for both check and exceeded on rejection")
        void shouldRecordBothMetrics() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();

            final var rejectedDecision =
                    RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(30), 30, 101, null);
            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(rateLimitResolver.resolveWebSocketConnectionLimit(any())).thenReturn(EffectiveRateLimit.of(100, 60));
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(rejectedDecision));

            filter.checkWebSocketRateLimit(ctx);

            verify(metrics).recordRateLimitCheck("my-service", false, 0L);
            verify(metrics).recordRateLimitExceeded("my-service", "ws_connection");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should allow request when rate limit check fails")
        void shouldAllowOnError() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();

            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis unavailable")));

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx).next();
        }
    }

    @Nested
    @DisplayName("WS_CLOSE_CODE_RATE_LIMITED constant")
    class ConstantTests {

        @Test
        @DisplayName("Should have correct close code value")
        void shouldHaveCorrectCloseCode() {
            assertEquals((short) 4429, WebSocketRateLimitFilter.WS_CLOSE_CODE_RATE_LIMITED);
        }
    }
}
