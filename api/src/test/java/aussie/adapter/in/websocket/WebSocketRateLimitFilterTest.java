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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.common.context.ClientContext;
import aussie.common.context.RouteContextAttributes;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
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

    @Mock
    private ProxyErrorWriter errorWriter;

    @Mock
    private ClientContextResolver clientContextResolver;

    private WebSocketRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new WebSocketRateLimitFilter(
                rateLimiter,
                config,
                rateLimitResolver,
                serviceRegistry,
                metrics,
                securityEventDispatcher,
                errorWriter,
                clientContextResolver);

        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(ctx.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.empty());
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
        lenient().when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext("unknown", false, null));
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
        @DisplayName("Should use the resolved service ID for gateway routes")
        void shouldUseResolvedServiceIdForGatewayRoutes() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/gateway/ws/echo");
            mockDefaultClientId();
            final var route = mock(RouteMatch.class);
            final var registration = mock(ServiceRegistration.class);
            when(registration.serviceId()).thenReturn("chat-service");
            when(route.service()).thenReturn(registration);
            when(ctx.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route));
            when(rateLimitResolver.resolveWebSocketConnectionLimit(Optional.of(registration)))
                    .thenReturn(EffectiveRateLimit.of(10, 60));
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            filter.checkWebSocketRateLimit(ctx);

            final var keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());
            assertEquals("chat-service", keyCaptor.getValue().serviceId());
            verify(serviceRegistry, never()).findRoute(anyString(), anyString());
            verify(serviceRegistry, never()).getServiceForRateLimiting(anyString());
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
    @DisplayName("pre-authentication identity")
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
        @DisplayName("uses only the canonical network identity")
        void shouldUseCanonicalNetworkIdentity() {
            setupRateLimitPath();
            when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext("10.0.0.5", false, null));

            filter.checkWebSocketRateLimit(ctx);

            final var keyCaptor = org.mockito.ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());
            assertEquals("ip:10.0.0.5", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("unverified credentials cannot rotate the network bucket")
        void shouldIgnoreUnverifiedCredentials() {
            setupRateLimitPath();
            final var cookie = mock(Cookie.class);
            lenient().when(cookie.getValue()).thenReturn("attacker-session");
            lenient().when(request.getCookie("aussie_session")).thenReturn(cookie);
            lenient().when(request.getHeader("Authorization")).thenReturn("Bearer attacker-token");
            lenient().when(request.getHeader("X-API-Key-ID")).thenReturn("attacker-key");
            when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext("192.0.2.10", false, null));

            filter.checkWebSocketRateLimit(ctx);

            final var keyCaptor = org.mockito.ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());
            assertEquals("ip:192.0.2.10", keyCaptor.getValue().clientId());
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
        @DisplayName("delegates to ProxyErrorWriter with the 429 ProblemDetail + rate-limit headers")
        void shouldDelegateToErrorWriterWhenExceeded() {
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
            final var problemCaptor = org.mockito.ArgumentCaptor.forClass(ProblemDetail.class);
            verify(errorWriter)
                    .writeRateLimit(
                            eq(ctx),
                            problemCaptor.capture(),
                            eq("my-service"),
                            eq(30L),
                            eq(100L),
                            eq(resetAt.getEpochSecond()));
            final var captured = problemCaptor.getValue();
            assertEquals(429, captured.status());
            assertEquals("Too Many Requests", captured.title());
            assertEquals(30L, captured.extras().get("retryAfter"));
            assertEquals(100L, captured.extras().get("limit"));
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
        @DisplayName("Should fail closed when rate limit check fails")
        void shouldFailClosedOnError() {
            mockWebSocketUpgrade();
            mockRateLimitingEnabled();
            when(request.path()).thenReturn("/my-service/ws");
            mockDefaultClientId();

            when(serviceRegistry.getServiceForRateLimiting("my-service"))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Redis unavailable")));

            filter.checkWebSocketRateLimit(ctx);

            verify(ctx, never()).next();
            verify(errorWriter)
                    .write(
                            eq(ctx),
                            eq(ProblemDetail.serviceUnavailable("Rate limit service unavailable")),
                            eq("my-service"));
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
