package aussie.adapter.in.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;

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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.http.GatewayCorsConfig;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.auth.OidcTokenValidator.TokenParseException;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.common.context.ClientContext;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.model.websocket.WebSocketProxySession;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.auth.JwksCacheService.JwksFetchException;
import aussie.core.service.ratelimit.WebSocketRateLimitService;
import aussie.core.service.routing.UpstreamAddressResolver;

@DisplayName("WebSocketGateway (unit)")
@ExtendWith(MockitoExtension.class)
class WebSocketGatewayUnitTest {

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

    @Mock
    private GatewayCorsConfig gatewayCorsConfig;

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
                addressResolver,
                gatewayCorsConfig);

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

    private void mockRequestPath(String path) {
        when(request.path()).thenReturn(path);
        lenient().when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        lenient().when(request.absoluteURI()).thenReturn("http://localhost:8080" + path);
        lenient().when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, "127.0.0.1"));
    }

    @Nested
    @DisplayName("buildRequest")
    class BuildRequestTests {

        @Test
        @DisplayName("shouldSetClientIpToUnknownWhenRemoteAddressIsNull")
        void shouldSetClientIpToUnknownWhenRemoteAddressIsNull() {
            mockRequestPath("/gateway/ws");
            when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext(null, false, null));
            when(config.maxConnections()).thenReturn(10000);

            final var captor = ArgumentCaptor.forClass(WebSocketUpgradeRequest.class);
            when(gatewayUseCase.upgradeGateway(captor.capture()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            final var capturedRequest = captor.getValue();
            assertEquals("unknown", capturedRequest.clientIp());
        }

        @Test
        @DisplayName("shouldSetClientIpFromRemoteAddress")
        void shouldSetClientIpFromRemoteAddress() {
            mockRequestPath("/gateway/ws");
            when(clientContextResolver.getOrCompute(ctx)).thenReturn(new ClientContext("127.0.0.1", false, null));
            when(config.maxConnections()).thenReturn(10000);

            final var captor = ArgumentCaptor.forClass(WebSocketUpgradeRequest.class);
            when(gatewayUseCase.upgradeGateway(captor.capture()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            final var capturedRequest = captor.getValue();
            assertEquals("127.0.0.1", capturedRequest.clientIp());
        }

        @Test
        @DisplayName("shouldCaptureMultipleHeaderValues")
        void shouldCaptureMultipleHeaderValues() {
            final var headers = MultiMap.caseInsensitiveMultiMap();
            headers.add("X-Custom", "value1");
            headers.add("X-Custom", "value2");
            when(request.path()).thenReturn("/gateway/ws");
            when(request.headers()).thenReturn(headers);
            lenient().when(request.absoluteURI()).thenReturn("http://localhost:8080/gateway/ws");
            lenient().when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(12345, "10.0.0.1"));
            when(config.maxConnections()).thenReturn(10000);

            final var captor = ArgumentCaptor.forClass(WebSocketUpgradeRequest.class);
            when(gatewayUseCase.upgradeGateway(captor.capture()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            final var capturedRequest = captor.getValue();
            final var customHeaders = capturedRequest.headers().get("X-Custom");
            assertEquals(2, customHeaders.size());
        }
    }

    @Nested
    @DisplayName("handlePassThroughUpgrade path parsing")
    class PassThroughPathParsingTests {

        @Test
        @DisplayName("shouldExtractServiceIdWithTrailingSlash")
        void shouldExtractServiceIdWithTrailingSlash() {
            mockRequestPath("/my-service/");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradePassThrough(any(), any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("my-service")));

            gateway.handlePassThroughUpgrade(ctx);

            verify(gatewayUseCase).upgradePassThrough(any(), any());
        }
    }

    @Nested
    @DisplayName("mapErrorToStatusCode cause chain traversal")
    class ErrorCauseChainTests {

        @Test
        @DisplayName("shouldReturnDefaultStatusCodeWhenCauseChainEndsWithNull")
        void shouldReturnDefaultStatusCodeWhenCauseChainEndsWithNull() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToStatusCode", Throwable.class);
            method.setAccessible(true);

            // A chain of generic exceptions with no recognized types
            final var inner = new RuntimeException("inner");
            final var outer = new RuntimeException("outer", inner);

            final var result = (int) method.invoke(gateway, outer);
            assertEquals(500, result);
        }

        @Test
        @DisplayName("shouldFindIllegalArgumentExceptionInCauseChain")
        void shouldFindIllegalArgumentExceptionInCauseChain() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToStatusCode", Throwable.class);
            method.setAccessible(true);

            final var root = new IllegalArgumentException("bad");
            final var wrapped = new RuntimeException("wrap", root);

            final var result = (int) method.invoke(gateway, wrapped);
            assertEquals(400, result);
        }
    }

    @Nested
    @DisplayName("onSessionInvalidated with active sessions")
    class SessionInvalidatedWithSessionsTests {

        @Test
        @DisplayName("shouldCloseMatchingSessionsOnSessionInvalidation")
        void shouldCloseMatchingSessionsOnSessionInvalidation() throws Exception {
            // Access activeSessions map via reflection
            final var activeSessionsField = WebSocketGateway.class.getDeclaredField("activeSessions");
            activeSessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var activeSessions = (java.util.Map<String, WebSocketProxySession>) activeSessionsField.get(gateway);

            final var session = mock(WebSocketProxySession.class);
            final var event = SessionInvalidatedEvent.forSession("auth-session-1");
            when(session.shouldCloseFor(event)).thenReturn(true);

            activeSessions.put("ws-session-1", session);

            gateway.onSessionInvalidated(event);

            verify(session).closeWithReason((short) 1000, "Session logged out");
        }

        @Test
        @DisplayName("shouldNotCloseNonMatchingSessionsOnSessionInvalidation")
        void shouldNotCloseNonMatchingSessionsOnSessionInvalidation() throws Exception {
            final var activeSessionsField = WebSocketGateway.class.getDeclaredField("activeSessions");
            activeSessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var activeSessions = (java.util.Map<String, WebSocketProxySession>) activeSessionsField.get(gateway);

            final var session = mock(WebSocketProxySession.class);
            final var event = SessionInvalidatedEvent.forSession("auth-session-1");
            when(session.shouldCloseFor(event)).thenReturn(false);

            activeSessions.put("ws-session-1", session);

            gateway.onSessionInvalidated(event);

            verify(session, never()).closeWithReason(any(short.class), anyString());
        }

        @Test
        @DisplayName("shouldCloseMultipleMatchingSessionsOnUserLogout")
        void shouldCloseMultipleMatchingSessionsOnUserLogout() throws Exception {
            final var activeSessionsField = WebSocketGateway.class.getDeclaredField("activeSessions");
            activeSessionsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var activeSessions = (java.util.Map<String, WebSocketProxySession>) activeSessionsField.get(gateway);

            final var session1 = mock(WebSocketProxySession.class);
            final var session2 = mock(WebSocketProxySession.class);
            final var session3 = mock(WebSocketProxySession.class);
            final var event = SessionInvalidatedEvent.forUser("user-123");
            when(session1.shouldCloseFor(event)).thenReturn(true);
            when(session2.shouldCloseFor(event)).thenReturn(false);
            when(session3.shouldCloseFor(event)).thenReturn(true);

            activeSessions.put("ws-1", session1);
            activeSessions.put("ws-2", session2);
            activeSessions.put("ws-3", session3);

            gateway.onSessionInvalidated(event);

            verify(session1).closeWithReason((short) 1000, "Session logged out");
            verify(session2, never()).closeWithReason(any(short.class), anyString());
            verify(session3).closeWithReason((short) 1000, "Session logged out");
        }
    }

    @Nested
    @DisplayName("getPort edge cases")
    class GetPortEdgeCaseTests {

        @Test
        @DisplayName("shouldReturn80ForNonWssSchemeWithoutPort")
        void shouldReturn80ForNonWssSchemeWithoutPort() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("http://example.com/ws"));
            assertEquals(80, result);
        }

        @Test
        @DisplayName("shouldReturn443ForWssSchemeWithoutPort")
        void shouldReturn443ForWssSchemeWithoutPort() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("wss://example.com/ws"));
            assertEquals(443, result);
        }

        @Test
        @DisplayName("shouldReturnExplicitPortWhenProvided")
        void shouldReturnExplicitPortWhenProvided() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("getPort", URI.class);
            method.setAccessible(true);

            final var result = (int) method.invoke(gateway, URI.create("ws://example.com:9090/ws"));
            assertEquals(9090, result);
        }
    }

    @Test
    @DisplayName("backendPath should preserve raw paths and queries")
    void backendPathShouldPreserveRawPathsAndQueries() throws Exception {
        final var method = WebSocketGateway.class.getDeclaredMethod("backendPath", URI.class);
        method.setAccessible(true);

        final var result = method.invoke(gateway, URI.create("wss://example.com/a%20b?token=a%2Fb"));

        assertEquals("/a%20b?token=a%2Fb", result);
    }

    @Nested
    @DisplayName("handleGatewayUpgrade path edge cases")
    class GatewayUpgradePathTests {

        @Test
        @DisplayName("shouldUseRootPathWhenGatewayPathIsEmpty")
        void shouldUseRootPathWhenGatewayPathIsEmpty() {
            mockRequestPath("/gateway");
            when(config.maxConnections()).thenReturn(10000);

            final var captor = ArgumentCaptor.forClass(WebSocketUpgradeRequest.class);
            when(gatewayUseCase.upgradeGateway(captor.capture()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.RouteNotFound("/")));

            gateway.handleGatewayUpgrade(ctx);

            assertEquals("/", captor.getValue().path());
        }
    }

    @Nested
    @DisplayName("handlePassThroughUpgrade path edge cases")
    class PassThroughPathEdgeCaseTests {

        @Test
        @DisplayName("shouldUseRootPathWhenNoSubPathAfterServiceId")
        void shouldUseRootPathWhenNoSubPathAfterServiceId() {
            mockRequestPath("/my-service");
            when(config.maxConnections()).thenReturn(10000);

            final var captor = ArgumentCaptor.forClass(WebSocketUpgradeRequest.class);
            when(gatewayUseCase.upgradePassThrough(any(), captor.capture()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("my-service")));

            gateway.handlePassThroughUpgrade(ctx);

            assertEquals("/", captor.getValue().path());
            verify(gatewayUseCase).upgradePassThrough(any(), any());
        }
    }

    @Nested
    @DisplayName("connection limit")
    class ConnectionLimitTests {

        @Test
        @DisplayName("shouldReturn503WhenConnectionLimitReached")
        void shouldReturn503WhenConnectionLimitReached() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(0);

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatus(503)));
        }
    }

    @Nested
    @DisplayName("mapErrorToStatusCode with specific exception types")
    class ErrorMappingTests {

        @Test
        @DisplayName("shouldReturn502ForJwksFetchException")
        void shouldReturn502ForJwksFetchException() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToStatusCode", Throwable.class);
            method.setAccessible(true);

            final var error = new JwksFetchException("JWKS unavailable");
            final var result = (int) method.invoke(gateway, error);
            assertEquals(502, result);
        }

        @Test
        @DisplayName("shouldReturn502ForTokenParseException")
        void shouldReturn502ForTokenParseException() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToStatusCode", Throwable.class);
            method.setAccessible(true);

            final var error = new TokenParseException("parse failed", new RuntimeException());
            final var result = (int) method.invoke(gateway, error);
            assertEquals(502, result);
        }

        @Test
        @DisplayName("shouldReturn502ForWrappedJwksFetchException")
        void shouldReturn502ForWrappedJwksFetchException() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToStatusCode", Throwable.class);
            method.setAccessible(true);

            final var inner = new JwksFetchException("JWKS error");
            final var outer = new RuntimeException("wrapper", inner);
            final var result = (int) method.invoke(gateway, outer);
            assertEquals(502, result);
        }
    }

    @Nested
    @DisplayName("mapErrorToMessage")
    class ErrorMessageTests {

        @Test
        @DisplayName("shouldReturnBadRequestMessageFor400")
        void shouldReturnBadRequestMessageFor400() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(gateway, 400);
            assertEquals("Bad request", result);
        }

        @Test
        @DisplayName("shouldReturnIdpUnavailableMessageFor502")
        void shouldReturnIdpUnavailableMessageFor502() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(gateway, 502);
            assertEquals("Identity provider unavailable", result);
        }

        @Test
        @DisplayName("shouldReturnInternalErrorMessageForDefaultCode")
        void shouldReturnInternalErrorMessageForDefaultCode() throws Exception {
            final var method = WebSocketGateway.class.getDeclaredMethod("mapErrorToMessage", int.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(gateway, 500);
            assertEquals("Internal error", result);
        }
    }

    @Nested
    @DisplayName("handleUpgrade result types")
    class HandleUpgradeResultTests {

        @Test
        @DisplayName("shouldReturn401ForUnauthorizedResult")
        void shouldReturn401ForUnauthorizedResult() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.Unauthorized("Invalid token")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatus(401)));
        }

        @Test
        @DisplayName("shouldReturn403ForForbiddenResult")
        void shouldReturn403ForForbiddenResult() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.Forbidden("Access denied")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatus(403)));
        }

        @Test
        @DisplayName("shouldReturn404ForServiceNotFoundResult")
        void shouldReturn404ForServiceNotFoundResult() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.ServiceNotFound("unknown-svc")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatus(404)));
        }

        @Test
        @DisplayName("shouldReturn400ForNotWebSocketResult")
        void shouldReturn400ForNotWebSocketResult() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom().item(new WebSocketUpgradeResult.NotWebSocket("/ws")));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter).write(eq(ctx), argThat(problemWithStatus(400)));
        }

        @Test
        @DisplayName("shouldReturn429ForRateLimitedResult")
        void shouldReturn429ForRateLimitedResult() {
            mockRequestPath("/gateway/ws");
            when(config.maxConnections()).thenReturn(10000);
            when(gatewayUseCase.upgradeGateway(any()))
                    .thenReturn(Uni.createFrom()
                            .item(new WebSocketUpgradeResult.RateLimited(
                                    60, 100, Instant.now().getEpochSecond() + 60)));

            gateway.handleGatewayUpgrade(ctx);

            verify(errorWriter)
                    .writeRateLimit(eq(ctx), argThat(problemWithStatus(429)), any(), eq(60L), eq(100L), anyLong());
        }
    }
}
