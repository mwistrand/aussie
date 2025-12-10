package aussie.adapter.in.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("WebSocketUpgradeFilter")
class WebSocketUpgradeFilterTest {

    private WebSocketGateway webSocketGateway;
    private WebSocketUpgradeFilter filter;
    private RoutingContext ctx;
    private HttpServerRequest request;

    @BeforeEach
    void setUp() {
        webSocketGateway = mock(WebSocketGateway.class);
        filter = new WebSocketUpgradeFilter(webSocketGateway);
        ctx = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);
    }

    private void mockWebSocketUpgrade() {
        when(request.getHeader("Upgrade")).thenReturn("websocket");
        when(request.getHeader("Connection")).thenReturn("Upgrade");
    }

    private void mockRegularRequest() {
        when(request.getHeader("Upgrade")).thenReturn(null);
        when(request.getHeader("Connection")).thenReturn(null);
    }

    @Nested
    @DisplayName("WebSocket Detection")
    class WebSocketDetectionTests {

        @Test
        @DisplayName("Should pass through non-WebSocket requests")
        void shouldPassThroughNonWebSocketRequests() {
            mockRegularRequest();

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx).next();
            verify(webSocketGateway, never()).handleGatewayUpgrade(ctx);
            verify(webSocketGateway, never()).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should detect WebSocket upgrade with standard headers")
        void shouldDetectWebSocketUpgrade() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/my-service/ws/echo");

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx, never()).next();
            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should handle case-insensitive Upgrade header")
        void shouldHandleCaseInsensitiveUpgradeHeader() {
            when(request.getHeader("Upgrade")).thenReturn("WebSocket");
            when(request.getHeader("Connection")).thenReturn("upgrade");
            when(request.path()).thenReturn("/my-service/ws/echo");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should handle Connection header with multiple values")
        void shouldHandleConnectionHeaderWithMultipleValues() {
            when(request.getHeader("Upgrade")).thenReturn("websocket");
            when(request.getHeader("Connection")).thenReturn("keep-alive, Upgrade");
            when(request.path()).thenReturn("/my-service/ws/echo");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should pass through when Upgrade header is not websocket")
        void shouldPassThroughWhenUpgradeHeaderNotWebsocket() {
            when(request.getHeader("Upgrade")).thenReturn("h2c");
            when(request.getHeader("Connection")).thenReturn("Upgrade");

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx).next();
        }

        @Test
        @DisplayName("Should pass through when Connection header missing Upgrade")
        void shouldPassThroughWhenConnectionHeaderMissingUpgrade() {
            when(request.getHeader("Upgrade")).thenReturn("websocket");
            when(request.getHeader("Connection")).thenReturn("keep-alive");

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx).next();
        }
    }

    @Nested
    @DisplayName("Gateway Mode Routing")
    class GatewayModeRoutingTests {

        @Test
        @DisplayName("Should route /gateway/ paths to gateway handler")
        void shouldRouteGatewayPathsToGatewayHandler() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/gateway/ws/echo");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handleGatewayUpgrade(ctx);
            verify(webSocketGateway, never()).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should route /gateway/nested/path to gateway handler")
        void shouldRouteNestedGatewayPaths() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/gateway/ws/rooms/123");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handleGatewayUpgrade(ctx);
        }
    }

    @Nested
    @DisplayName("Pass-Through Mode Routing")
    class PassThroughModeRoutingTests {

        @Test
        @DisplayName("Should route service paths to pass-through handler")
        void shouldRouteServicePathsToPassThroughHandler() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/my-service/ws/echo");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
            verify(webSocketGateway, never()).handleGatewayUpgrade(ctx);
        }

        @Test
        @DisplayName("Should handle paths with multiple segments")
        void shouldHandlePathsWithMultipleSegments() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/chat-service/ws/rooms/123/messages");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }
    }

    @Nested
    @DisplayName("Reserved Path Handling")
    class ReservedPathHandlingTests {

        @ParameterizedTest
        @ValueSource(strings = {"/admin/ws/test", "/Admin/ws/test", "/ADMIN/anything"})
        @DisplayName("Should pass through /admin paths")
        void shouldPassThroughAdminPaths(String path) {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn(path);

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx).next();
            verify(webSocketGateway, never()).handleGatewayUpgrade(ctx);
            verify(webSocketGateway, never()).handlePassThroughUpgrade(ctx);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/q/health", "/Q/metrics", "/q/dev"})
        @DisplayName("Should pass through /q paths (Quarkus endpoints)")
        void shouldPassThroughQuarkusPaths(String path) {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn(path);

            filter.interceptWebSocketUpgrade(ctx);

            verify(ctx).next();
        }

        @ParameterizedTest
        @ValueSource(strings = {"/gateway/ws/test", "/Gateway/ws/test"})
        @DisplayName("Should NOT treat /gateway as reserved - routes to gateway handler")
        void shouldRouteGatewayPaths(String path) {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn(path);

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handleGatewayUpgrade(ctx);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle root path WebSocket upgrade")
        void shouldHandleRootPathWebSocketUpgrade() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/");

            filter.interceptWebSocketUpgrade(ctx);

            // Root path is not reserved, so it goes to pass-through
            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should handle single segment path")
        void shouldHandleSingleSegmentPath() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/my-service");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }

        @Test
        @DisplayName("Should handle path with trailing slash")
        void shouldHandlePathWithTrailingSlash() {
            mockWebSocketUpgrade();
            when(request.path()).thenReturn("/my-service/ws/");

            filter.interceptWebSocketUpgrade(ctx);

            verify(webSocketGateway).handlePassThroughUpgrade(ctx);
        }
    }
}
