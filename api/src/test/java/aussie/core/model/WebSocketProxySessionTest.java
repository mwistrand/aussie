package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.config.WebSocketConfigMapping;

@DisplayName("WebSocketProxySession")
class WebSocketProxySessionTest {

    private Vertx vertx;
    private ServerWebSocket clientSocket;
    private WebSocket backendSocket;
    private WebSocketConfigMapping config;
    private WebSocketConfigMapping.PingConfig pingConfig;

    @BeforeEach
    void setUp() {
        vertx = mock(Vertx.class);
        clientSocket = mock(ServerWebSocket.class);
        backendSocket = mock(WebSocket.class);
        config = mock(WebSocketConfigMapping.class);
        pingConfig = mock(WebSocketConfigMapping.PingConfig.class);

        // Default config values
        when(config.idleTimeout()).thenReturn(Duration.ofMinutes(5));
        when(config.maxLifetime()).thenReturn(Duration.ofHours(24));
        when(config.ping()).thenReturn(pingConfig);
        when(pingConfig.enabled()).thenReturn(true);
        when(pingConfig.interval()).thenReturn(Duration.ofSeconds(30));
        when(pingConfig.timeout()).thenReturn(Duration.ofSeconds(10));

        // Timer returns a timer ID
        when(vertx.setTimer(anyLong(), any())).thenReturn(1L);
        when(vertx.setPeriodic(anyLong(), any())).thenReturn(2L);
    }

    @Nested
    @DisplayName("Constructor and Initialization")
    class InitializationTests {

        @Test
        @DisplayName("Should initialize with correct session ID")
        void shouldInitializeWithCorrectSessionId() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertEquals("test-session", session.sessionId());
        }

        @Test
        @DisplayName("Should set connectedAt to current time")
        void shouldSetConnectedAtToCurrentTime() {
            var before = Instant.now();
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);
            var after = Instant.now();

            assertNotNull(session.connectedAt());
            assertTrue(session.connectedAt().compareTo(before) >= 0);
            assertTrue(session.connectedAt().compareTo(after) <= 0);
        }

        @Test
        @DisplayName("Should set lastActivity to current time")
        void shouldSetLastActivityToCurrentTime() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertNotNull(session.lastActivity());
        }

        @Test
        @DisplayName("Should not be closing initially")
        void shouldNotBeClosingInitially() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertFalse(session.isClosing());
        }
    }

    @Nested
    @DisplayName("start()")
    class StartTests {

        @Test
        @DisplayName("Should set up client message handler")
        void shouldSetUpClientMessageHandler() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(clientSocket).handler(any());
        }

        @Test
        @DisplayName("Should set up backend message handler")
        void shouldSetUpBackendMessageHandler() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(backendSocket).handler(any());
        }

        @Test
        @DisplayName("Should set up close handlers")
        void shouldSetUpCloseHandlers() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(clientSocket).closeHandler(any());
            verify(backendSocket).closeHandler(any());
        }

        @Test
        @DisplayName("Should set up exception handlers")
        void shouldSetUpExceptionHandlers() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(clientSocket).exceptionHandler(any());
            verify(backendSocket).exceptionHandler(any());
        }

        @Test
        @DisplayName("Should set up pong handler")
        void shouldSetUpPongHandler() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(clientSocket).pongHandler(any());
        }

        @Test
        @DisplayName("Should start idle timer")
        void shouldStartIdleTimer() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(vertx).setTimer(eq(Duration.ofMinutes(5).toMillis()), any());
        }

        @Test
        @DisplayName("Should start max lifetime timer")
        void shouldStartMaxLifetimeTimer() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(vertx).setTimer(eq(Duration.ofHours(24).toMillis()), any());
        }

        @Test
        @DisplayName("Should start ping timer when enabled")
        void shouldStartPingTimerWhenEnabled() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(vertx).setPeriodic(eq(Duration.ofSeconds(30).toMillis()), any());
        }

        @Test
        @DisplayName("Should not start ping timer when disabled")
        void shouldNotStartPingTimerWhenDisabled() {
            when(pingConfig.enabled()).thenReturn(false);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(vertx, never()).setPeriodic(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("Message Forwarding")
    class MessageForwardingTests {

        @Test
        @DisplayName("Should forward client messages to backend")
        void shouldForwardClientMessagesToBackend() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Buffer>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(clientSocket).handler(handlerCaptor.capture());

            // Simulate receiving a message from client
            var buffer = Buffer.buffer("test message");
            handlerCaptor.getValue().handle(buffer);

            verify(backendSocket).write(buffer);
        }

        @Test
        @DisplayName("Should forward backend messages to client")
        void shouldForwardBackendMessagesToClient() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Buffer>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(backendSocket).handler(handlerCaptor.capture());

            // Simulate receiving a message from backend
            var buffer = Buffer.buffer("backend response");
            handlerCaptor.getValue().handle(buffer);

            verify(clientSocket).write(buffer);
        }
    }

    @Nested
    @DisplayName("closeWithReason()")
    class CloseWithReasonTests {

        @Test
        @DisplayName("Should close both sockets")
        void shouldCloseBothSockets() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.closeWithReason((short) 1000, "Test reason");

            verify(clientSocket).close((short) 1000, "Test reason");
            verify(backendSocket).close((short) 1000, "Test reason");
        }

        @Test
        @DisplayName("Should set closing flag")
        void shouldSetClosingFlag() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertFalse(session.isClosing());
            session.closeWithReason((short) 1000, "Test reason");
            assertTrue(session.isClosing());
        }

        @Test
        @DisplayName("Should be idempotent - second call has no effect")
        void shouldBeIdempotent() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.closeWithReason((short) 1000, "First close");
            session.closeWithReason((short) 1001, "Second close");

            // Close should only be called once
            verify(clientSocket).close((short) 1000, "First close");
            verify(backendSocket).close((short) 1000, "First close");
            verify(clientSocket, never()).close((short) 1001, "Second close");
        }

        @Test
        @DisplayName("Should cancel all timers")
        void shouldCancelAllTimers() {
            when(vertx.setTimer(anyLong(), any())).thenReturn(10L, 20L);
            when(vertx.setPeriodic(anyLong(), any())).thenReturn(30L);

            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);
            session.start();

            session.closeWithReason((short) 1000, "Test reason");

            // Verify timer cancellations (idle timer ID: 10, max lifetime ID: 20, ping ID: 30)
            verify(vertx).cancelTimer(10L);
            verify(vertx).cancelTimer(20L);
            verify(vertx).cancelTimer(30L);
        }
    }

    @Nested
    @DisplayName("Close Handlers")
    class CloseHandlerTests {

        @Test
        @DisplayName("Should close session when client disconnects")
        void shouldCloseSessionWhenClientDisconnects() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Void>> closeHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();
            verify(clientSocket).closeHandler(closeHandlerCaptor.capture());

            // Simulate client close
            closeHandlerCaptor.getValue().handle(null);

            assertTrue(session.isClosing());
        }

        @Test
        @DisplayName("Should close session when backend disconnects")
        void shouldCloseSessionWhenBackendDisconnects() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Void>> closeHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();
            verify(backendSocket).closeHandler(closeHandlerCaptor.capture());

            // Simulate backend close
            closeHandlerCaptor.getValue().handle(null);

            assertTrue(session.isClosing());
        }
    }

    @Nested
    @DisplayName("Exception Handlers")
    class ExceptionHandlerTests {

        @Test
        @DisplayName("Should close session on client error")
        void shouldCloseSessionOnClientError() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Throwable>> exceptionCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();
            verify(clientSocket).exceptionHandler(exceptionCaptor.capture());

            // Simulate client error
            exceptionCaptor.getValue().handle(new RuntimeException("Client error"));

            assertTrue(session.isClosing());
            verify(clientSocket).close(eq((short) 1011), anyString());
        }

        @Test
        @DisplayName("Should close session on backend error")
        void shouldCloseSessionOnBackendError() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Handler<Throwable>> exceptionCaptor = ArgumentCaptor.forClass(Handler.class);
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            session.start();
            verify(backendSocket).exceptionHandler(exceptionCaptor.capture());

            // Simulate backend error
            exceptionCaptor.getValue().handle(new RuntimeException("Backend error"));

            assertTrue(session.isClosing());
            verify(backendSocket).close(eq((short) 1011), anyString());
        }
    }

    @Nested
    @DisplayName("Timer Callbacks")
    class TimerCallbackTests {

        @Test
        @DisplayName("Should close session when idle timeout expires")
        void shouldCloseSessionWhenIdleTimeoutExpires() {
            // Capture the timer callback
            AtomicReference<Handler<Long>> idleTimerCallback = new AtomicReference<>();
            when(vertx.setTimer(eq(Duration.ofMinutes(5).toMillis()), any())).thenAnswer(invocation -> {
                idleTimerCallback.set(invocation.getArgument(1));
                return 1L;
            });

            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);
            session.start();

            // Trigger idle timeout
            idleTimerCallback.get().handle(1L);

            assertTrue(session.isClosing());
            verify(clientSocket).close((short) 1000, "Idle timeout exceeded");
        }

        @Test
        @DisplayName("Should close session when max lifetime expires")
        void shouldCloseSessionWhenMaxLifetimeExpires() {
            // Capture the timer callback (second call to setTimer is for max lifetime)
            AtomicReference<Handler<Long>> maxLifetimeCallback = new AtomicReference<>();
            when(vertx.setTimer(eq(Duration.ofHours(24).toMillis()), any())).thenAnswer(invocation -> {
                maxLifetimeCallback.set(invocation.getArgument(1));
                return 2L;
            });

            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);
            session.start();

            // Trigger max lifetime expiry
            maxLifetimeCallback.get().handle(2L);

            assertTrue(session.isClosing());
            verify(clientSocket).close((short) 1000, "Maximum connection lifetime exceeded");
        }
    }

    @Nested
    @DisplayName("Auth Session Tracking")
    class AuthSessionTrackingTests {

        @Test
        @DisplayName("Should store auth session ID when provided")
        void shouldStoreAuthSessionId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            assertEquals(Optional.of("auth-session-123"), session.authSessionId());
        }

        @Test
        @DisplayName("Should store user ID when provided")
        void shouldStoreUserId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            assertEquals(Optional.of("user-456"), session.userId());
        }

        @Test
        @DisplayName("Should have empty auth session ID when not provided")
        void shouldHaveEmptyAuthSessionId() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertTrue(session.authSessionId().isEmpty());
        }

        @Test
        @DisplayName("Should have empty user ID when not provided")
        void shouldHaveEmptyUserId() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            assertTrue(session.userId().isEmpty());
        }
    }

    @Nested
    @DisplayName("shouldCloseFor")
    class ShouldCloseForTests {

        @Test
        @DisplayName("Should close for matching session ID")
        void shouldCloseForMatchingSessionId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            var event = SessionInvalidatedEvent.forSession("auth-session-123");

            assertTrue(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("Should close for matching user ID")
        void shouldCloseForMatchingUserId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            var event = SessionInvalidatedEvent.forUser("user-456");

            assertTrue(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("Should not close for different session ID")
        void shouldNotCloseForDifferentSessionId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            var event = SessionInvalidatedEvent.forSession("different-session");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("Should not close for different user ID")
        void shouldNotCloseForDifferentUserId() {
            var session = new WebSocketProxySession(
                    "test-session",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-123"),
                    Optional.of("user-456"));

            var event = SessionInvalidatedEvent.forUser("different-user");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("Should not close unauthenticated session")
        void shouldNotCloseUnauthenticatedSession() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            var event = SessionInvalidatedEvent.forSession("any-session");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("Should not close unauthenticated session for user event")
        void shouldNotCloseUnauthenticatedSessionForUserEvent() {
            var session = new WebSocketProxySession("test-session", clientSocket, backendSocket, vertx, config);

            var event = SessionInvalidatedEvent.forUser("any-user");

            assertFalse(session.shouldCloseFor(event));
        }
    }
}
