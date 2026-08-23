package aussie.adapter.in.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
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

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.auth.OidcTokenValidator.TokenParseException;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.common.context.ClientContext;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.auth.JwksCacheService.JwksFetchException;
import aussie.core.service.ratelimit.WebSocketRateLimitService;
import aussie.core.service.routing.UpstreamAddressResolver;

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
    private OutboundHttpClients outboundClient;

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

    @Mock
    private ProxyErrorWriter errorWriter;

    @Mock
    private ClientContextResolver clientContextResolver;

    @Mock
    private UpstreamAddressResolver addressResolver;

    private WebSocketGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new WebSocketGateway(
                gatewayUseCase,
                config,
                vertx,
                outboundClient,
                metrics,
                rateLimitService,
                errorWriter,
                clientContextResolver,
                addressResolver);

        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(response.setStatusCode(anyInt())).thenReturn(response);
        lenient().when(response.putHeader(anyString(), anyString())).thenReturn(response);
        lenient().when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext(null, false, null));
        lenient()
                .when(addressResolver.resolve(any(URI.class)))
                .thenReturn(Uni.createFrom().item(SocketAddress.inetSocketAddress(443, "203.0.113.1")));
    }

    private static org.mockito.ArgumentMatcher<ProblemDetail> problemWithStatus(int status) {
        return p -> p != null && p.status() == status;
    }

    private static org.mockito.ArgumentMatcher<ProblemDetail> problemWithStatusAndTitle(int status, String title) {
        return p -> p != null && p.status() == status && title.equals(p.title());
    }

    private static org.mockito.ArgumentMatcher<ProblemDetail> problemWithStatusAndDetail(int status, String detail) {
        return p -> p != null && p.status() == status && detail.equals(p.detail());
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
            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndTitle(404, "Route Not Found")));
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
            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndTitle(404, "Route Not Found")));
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
            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndTitle(404, "Service Not Found")));
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

            verify(errorWriter)
                    .write(
                            eq(ctx),
                            argThat(problemWithStatusAndDetail(
                                    503, "Service temporarily unavailable: connection limit reached")));
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

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndDetail(401, "Invalid token")));
        }

        @Test
        @DisplayName("Should return 403 for Forbidden result")
        void shouldReturn403ForForbidden() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.Forbidden("Access denied")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndDetail(403, "Access denied")));
        }

        @Test
        @DisplayName("Should return 404 for RouteNotFound result")
        void shouldReturn404ForRouteNotFound() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndTitle(404, "Route Not Found")));
        }

        @Test
        @DisplayName("Should return 404 for ServiceNotFound result")
        void shouldReturn404ForServiceNotFound() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("unknown-svc")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndTitle(404, "Service Not Found")));
        }

        @Test
        @DisplayName("Should return 400 for NotWebSocket result")
        void shouldReturn400ForNotWebSocket() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket("/api/rest")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter)
                    .write(eq(ctx), argThat(problemWithStatusAndDetail(400, "Not a WebSocket endpoint: /api/rest")));
        }

        @Test
        @DisplayName("Should delegate 429 RateLimited result to errorWriter.writeRateLimit")
        void shouldReturn429ForRateLimited() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RateLimited(30, 100, 1709654400)));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter)
                    .writeRateLimit(
                            eq(ctx), argThat(problemWithStatus(429)), isNull(), eq(30L), eq(100L), eq(1709654400L));
            verify(metrics).recordRateLimitExceeded(isNull(), eq("ws_connection"));
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

            verify(errorWriter)
                    .write(eq(ctx), argThat(problemWithStatusAndDetail(502, "Identity provider unavailable")));
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

            verify(errorWriter)
                    .write(eq(ctx), argThat(problemWithStatusAndDetail(502, "Identity provider unavailable")));
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

            verify(errorWriter)
                    .write(eq(ctx), argThat(problemWithStatusAndDetail(502, "Identity provider unavailable")));
        }

        @Test
        @DisplayName("Should return 400 for IllegalArgumentException")
        void shouldReturn400ForIllegalArgumentException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(new IllegalArgumentException("Invalid parameter")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndDetail(400, "Bad request")));
        }

        @Test
        @DisplayName("Should return 500 for generic exceptions")
        void shouldReturn500ForGenericException() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Something went wrong")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatusAndDetail(500, "Internal error")));
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
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, 400);

            assertEquals("Bad request", result);
        }

        @Test
        @DisplayName("Should return identity provider unavailable for status 502")
        void shouldReturnIdpUnavailableMessage() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, 502);

            assertEquals("Identity provider unavailable", result);
        }

        @Test
        @DisplayName("Should return internal error for other status codes")
        void shouldReturnInternalErrorMessage() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);
            final var result = (String) method.invoke(gateway, 500);

            assertEquals("Internal error", result);
        }
    }
}
