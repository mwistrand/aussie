package aussie.adapter.in.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.auth.OidcTokenValidator.TokenParseException;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.service.auth.JwksCacheService.JwksFetchException;
import aussie.core.service.ratelimit.WebSocketRateLimitService;

@DisplayName("WebSocketGateway")
@ExtendWith(MockitoExtension.class)
class WebSocketGatewayTest {

    @Mock
    private WebSocketGatewayUseCase gatewayUseCase;

    @Mock
    private WebSocketConfig config;

    @Mock
    private Vertx vertx;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private WebSocketRateLimitService rateLimitService;

    @Mock
    private RoutingContext ctx;

    @Mock
    private HttpServerRequest request;

    @Mock
    private HttpServerResponse response;

    private WebSocketGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new WebSocketGateway(gatewayUseCase, config, vertx, metrics, rateLimitService);

        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
    }

    private void mockRequestPath(String path) {
        when(request.path()).thenReturn(path);
        lenient().when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        lenient().when(request.absoluteURI()).thenReturn("http://localhost:8080" + path);
        lenient().when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, "127.0.0.1"));
    }

    @Nested
    @DisplayName("handleGatewayUpgrade")
    class HandleGatewayUpgradeTests {

        @Test
        @DisplayName("Should strip /gateway prefix and pass remaining path")
        void shouldStripGatewayPrefix() {
            mockRequestPath("/gateway/ws/echo");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws/echo")));

            gateway.handleGatewayUpgrade(ctx);

            verify(gatewayUseCase).upgradeGateway(any());
            verify(response).setStatusCode(404);
            verify(response).end("Route not found: /ws/echo");
        }

        @Test
        @DisplayName("Should use / when path is exactly /gateway")
        void shouldDefaultToRootPath() {
            mockRequestPath("/gateway");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/")));

            gateway.handleGatewayUpgrade(ctx);

            verify(gatewayUseCase).upgradeGateway(any());
            verify(response).setStatusCode(404);
            verify(response).end("Route not found: /");
        }
    }

    @Nested
    @DisplayName("handlePassThroughUpgrade")
    class HandlePassThroughUpgradeTests {

        @Test
        @DisplayName("Should extract serviceId and path from pass-through URL")
        void shouldExtractServiceIdAndPath() {
            mockRequestPath("/my-service/ws/echo");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradePassThrough(eq("my-service"), any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("my-service")));

            gateway.handlePassThroughUpgrade(ctx);

            verify(gatewayUseCase).upgradePassThrough(eq("my-service"), any());
            verify(response).setStatusCode(404);
        }

        @Test
        @DisplayName("Should use / when no subpath exists")
        void shouldDefaultToRootPathWhenNoSubpath() {
            mockRequestPath("/my-service");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradePassThrough(eq("my-service"), any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("my-service")));

            gateway.handlePassThroughUpgrade(ctx);

            verify(gatewayUseCase).upgradePassThrough(eq("my-service"), any());
        }
    }

    @Nested
    @DisplayName("handleUpgrade - connection limit")
    class ConnectionLimitTests {

        @Test
        @DisplayName("Should return 503 when connection limit is reached")
        void shouldReturn503WhenLimitReached() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(0);
            lenient()
                    .when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(503);
            verify(response).end("Service temporarily unavailable: connection limit reached");
        }
    }

    @Nested
    @DisplayName("handleUpgrade - result handling")
    class ResultHandlingTests {

        @Test
        @DisplayName("Should return 401 for Unauthorized result")
        void shouldReturn401ForUnauthorized() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.Unauthorized("Invalid token")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(401);
            verify(response).end("Invalid token");
        }

        @Test
        @DisplayName("Should return 403 for Forbidden result")
        void shouldReturn403ForForbidden() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.Forbidden("Access denied")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(403);
            verify(response).end("Access denied");
        }

        @Test
        @DisplayName("Should return 404 for RouteNotFound result")
        void shouldReturn404ForRouteNotFound() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(404);
            verify(response).end("Route not found: /ws");
        }

        @Test
        @DisplayName("Should return 404 for ServiceNotFound result")
        void shouldReturn404ForServiceNotFound() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("unknown-svc")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(404);
            verify(response).end("Service not found: unknown-svc");
        }

        @Test
        @DisplayName("Should return 400 for NotWebSocket result")
        void shouldReturn400ForNotWebSocket() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket("/api/rest")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(400);
            verify(response).end("Not a WebSocket endpoint: /api/rest");
        }

        @Test
        @DisplayName("Should return 429 with headers for RateLimited result")
        void shouldReturn429ForRateLimited() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RateLimited(30, 100, 1709654400)));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(429);
            verify(response).putHeader("Retry-After", "30");
            verify(response).putHeader("X-RateLimit-Limit", "100");
            verify(response).putHeader("X-RateLimit-Remaining", "0");
            verify(response).putHeader("X-RateLimit-Reset", "1709654400");
            verify(response).end("Rate limit exceeded. Retry after 30 seconds.");
            verify(metrics).recordRateLimitExceeded("unknown", "ws_connection");
        }
    }

    @Nested
    @DisplayName("handleUpgrade - error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should return 502 for JwksFetchException")
        void shouldReturn502ForJwksFetchException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(new JwksFetchException("JWKS unavailable")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(502);
            verify(response).end("Identity provider unavailable");
        }

        @Test
        @DisplayName("Should return 502 for TokenParseException")
        void shouldReturn502ForTokenParseException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(
                            Uni.createFrom().failure(new TokenParseException("Parse failed", new RuntimeException())));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(502);
            verify(response).end("Identity provider unavailable");
        }

        @Test
        @DisplayName("Should return 502 for wrapped JwksFetchException in cause chain")
        void shouldReturn502ForWrappedJwksFetchException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            final var wrappedException = new RuntimeException("Wrapper", new JwksFetchException("JWKS unavailable"));
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(wrappedException));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(502);
            verify(response).end("Identity provider unavailable");
        }

        @Test
        @DisplayName("Should return 400 for IllegalArgumentException")
        void shouldReturn400ForIllegalArgumentException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Invalid parameter")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(400);
            verify(response).end("Bad request: Invalid parameter");
        }

        @Test
        @DisplayName("Should return 500 for generic exceptions")
        void shouldReturn500ForGenericException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Something went wrong")));

            gateway.handleGatewayUpgrade(ctx);

            verify(response).setStatusCode(500);
            verify(response).end("Internal error");
        }
    }

    @Nested
    @DisplayName("getPort")
    class GetPortTests {

        @Test
        @DisplayName("Should return explicit port from URI")
        void shouldReturnExplicitPort() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("ws://example.com:9090/ws"));

            assertEquals(9090, result);
        }

        @Test
        @DisplayName("Should return 443 for wss scheme without explicit port")
        void shouldReturn443ForWss() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("wss://example.com/ws"));

            assertEquals(443, result);
        }

        @Test
        @DisplayName("Should return 80 for ws scheme without explicit port")
        void shouldReturn80ForWs() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("ws://example.com/ws"));

            assertEquals(80, result);
        }
    }

    @Nested
    @DisplayName("extractClientId")
    class ExtractClientIdTests {

        @Test
        @DisplayName("Should return session cookie value as highest priority")
        void shouldReturnSessionCookie() throws Exception {
            final var cookie = mock(Cookie.class);
            when(cookie.getValue()).thenReturn("abc123");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("session:abc123", result);
        }

        @Test
        @DisplayName("Should return session header when no cookie")
        void shouldReturnSessionHeader() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn("sess-456");

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("session:sess-456", result);
        }

        @Test
        @DisplayName("Should return bearer hash when no session identifiers")
        void shouldReturnBearerHash() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn("Bearer my-token-value");

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            final var expectedHash = Integer.toHexString("my-token-value".hashCode());
            assertEquals("bearer:" + expectedHash, result);
        }

        @Test
        @DisplayName("Should return API key ID when no session or bearer")
        void shouldReturnApiKeyId() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn("key-789");

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("apikey:key-789", result);
        }

        @Test
        @DisplayName("Should return X-Forwarded-For IP when no other identifiers")
        void shouldReturnForwardedIp() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 192.168.1.1");

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("ip:10.0.0.1", result);
        }

        @Test
        @DisplayName("Should return ip:unknown as fallback")
        void shouldReturnUnknownFallback() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn(null);
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("ip:unknown", result);
        }

        @Test
        @DisplayName("Should skip non-Bearer Authorization header")
        void shouldSkipNonBearerAuth() throws Exception {
            when(request.getCookie("aussie_session")).thenReturn(null);
            when(request.getHeader("X-Session-ID")).thenReturn(null);
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
            when(request.getHeader("X-API-Key-ID")).thenReturn(null);
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);

            final var method = WebSocketGateway.class.getDeclaredMethod("extractClientId", RoutingContext.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, ctx);

            assertEquals("ip:unknown", result);
        }
    }

    @Nested
    @DisplayName("getActiveSessionCount")
    class ActiveSessionCountTests {

        @Test
        @DisplayName("Should return 0 when no active sessions")
        void shouldReturnZeroInitially() {
            assertEquals(0, gateway.getActiveSessionCount());
        }
    }

    @Nested
    @DisplayName("onSessionInvalidated")
    class SessionInvalidatedTests {

        @Test
        @DisplayName("Should not throw when no active sessions match event")
        void shouldHandleNoMatchingSessions() {
            final var event = aussie.core.model.session.SessionInvalidatedEvent.forSession("nonexistent");

            gateway.onSessionInvalidated(event);
        }
    }

    @Nested
    @DisplayName("mapErrorToMessage")
    class MapErrorToMessageTests {

        @Test
        @DisplayName("Should return bad request message for status 400")
        void shouldReturnBadRequestMessage() throws Exception {
            final var method =
                    WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", Throwable.class, int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, new IllegalArgumentException("bad param"), 400);

            assertEquals("Bad request: bad param", result);
        }

        @Test
        @DisplayName("Should return identity provider unavailable for status 502")
        void shouldReturnIdpUnavailableMessage() throws Exception {
            final var method =
                    WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", Throwable.class, int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, new RuntimeException("fail"), 502);

            assertEquals("Identity provider unavailable", result);
        }

        @Test
        @DisplayName("Should return internal error for other status codes")
        void shouldReturnInternalErrorMessage() throws Exception {
            final var method =
                    WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", Throwable.class, int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, new RuntimeException("fail"), 500);

            assertEquals("Internal error", result);
        }
    }
}
