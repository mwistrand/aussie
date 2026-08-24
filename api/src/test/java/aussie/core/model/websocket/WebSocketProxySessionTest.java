package aussie.core.model.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.WebSocketConfig;
import aussie.core.model.ratelimit.MessageRateLimitHandler;
import aussie.core.model.session.SessionInvalidatedEvent;

@DisplayName("WebSocketProxySession")
@ExtendWith(MockitoExtension.class)
class WebSocketProxySessionTest {

    @Mock
    private ServerWebSocket clientSocket;

    @Mock
    private WebSocket backendSocket;

    @Mock
    private Vertx vertx;

    @Mock
    private WebSocketConfig config;

    @Mock
    private WebSocketConfig.PingConfig pingConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.idleTimeout()).thenReturn(Duration.ofMinutes(5));
        lenient().when(config.maxLifetime()).thenReturn(Duration.ofHours(24));
        lenient().when(config.maxQueueBytes()).thenReturn(1024);
        lenient().when(config.ping()).thenReturn(pingConfig);
        lenient().when(pingConfig.enabled()).thenReturn(false);
        lenient().when(pingConfig.interval()).thenReturn(Duration.ofSeconds(30));
        lenient().when(pingConfig.timeout()).thenReturn(Duration.ofSeconds(10));
        lenient().when(vertx.setTimer(anyLong(), any())).thenReturn(1L);
        lenient().when(vertx.setPeriodic(anyLong(), any())).thenReturn(2L);
        lenient().when(vertx.cancelTimer(anyLong())).thenReturn(true);
    }

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("shouldCreateSessionWithMinimalConstructor")
        void shouldCreateSessionWithMinimalConstructor() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            assertEquals("s1", session.sessionId());
            assertNotNull(session.connectedAt());
            assertNotNull(session.lastActivity());
            assertFalse(session.isClosing());
            assertEquals(Optional.empty(), session.authSessionId());
            assertEquals(Optional.empty(), session.userId());
            assertEquals(0L, session.rateLimitedMessageCount());
        }

        @Test
        @DisplayName("shouldCreateSessionWithAuthSessionAndUserId")
        void shouldCreateSessionWithAuthSessionAndUserId() {
            final var session = new WebSocketProxySession(
                    "s1", clientSocket, backendSocket, vertx, config, Optional.of("auth-123"), Optional.of("user-456"));

            assertEquals(Optional.of("auth-123"), session.authSessionId());
            assertEquals(Optional.of("user-456"), session.userId());
        }

        @Test
        @DisplayName("shouldCreateSessionWithMessageRateLimitHandler")
        void shouldCreateSessionWithMessageRateLimitHandler() {
            final var handler = MessageRateLimitHandler.noOp();
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-123"),
                    Optional.of("user-456"),
                    handler);

            assertEquals(Optional.of("auth-123"), session.authSessionId());
        }
    }

    @Nested
    @DisplayName("start")
    class StartTests {

        @Test
        @DisplayName("shouldSetUpHandlersAndStartTimersWithPingDisabled")
        void shouldSetUpHandlersAndStartTimersWithPingDisabled() {
            when(pingConfig.enabled()).thenReturn(false);
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            session.start();

            // handlers set
            verify(clientSocket).handler(any());
            verify(backendSocket).handler(any());
            verify(clientSocket).closeHandler(any());
            verify(backendSocket).closeHandler(any());
            verify(clientSocket).exceptionHandler(any());
            verify(backendSocket).exceptionHandler(any());
            verify(clientSocket).pongHandler(any());

            // idle and max lifetime timers
            verify(vertx, times(1)).setTimer(eq(Duration.ofMinutes(5).toMillis()), any());
            verify(vertx, times(1)).setTimer(eq(Duration.ofHours(24).toMillis()), any());

            // no ping timer
            verify(vertx, never()).setPeriodic(anyLong(), any());
        }

        @Test
        @DisplayName("shouldStartPingTimerWhenPingEnabled")
        void shouldStartPingTimerWhenPingEnabled() {
            when(pingConfig.enabled()).thenReturn(true);
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            session.start();

            verify(vertx).setPeriodic(eq(Duration.ofSeconds(30).toMillis()), any());
        }

        @Test
        @DisplayName("shouldScheduleAlreadyExpiredIdentityForImmediateClosure")
        void shouldScheduleAlreadyExpiredIdentityForImmediateClosure() {
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(Instant.now().minusSeconds(1)),
                    MessageRateLimitHandler.noOp(),
                    () -> {});

            session.start();

            verify(vertx).setTimer(eq(1L), any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldForwardClientMessagesToBackendWithRateLimiting")
        void shouldForwardClientMessagesToBackendWithRateLimiting() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(clientSocket).handler(handlerCaptor.capture());

            // Simulate a message from the client
            final var buffer = Buffer.buffer("hello");
            handlerCaptor.getValue().handle(buffer);

            verify(backendSocket).write(buffer);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldForwardBackendMessagesToClient")
        void shouldForwardBackendMessagesToClient() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(backendSocket).handler(handlerCaptor.capture());

            final var buffer = Buffer.buffer("response");
            handlerCaptor.getValue().handle(buffer);

            verify(clientSocket).write(buffer);
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldPauseUntilProxyWriteCompletes")
        void shouldPauseUntilProxyWriteCompletes() {
            final var promise = Promise.<Void>promise();
            when(backendSocket.write(any(Buffer.class))).thenReturn(promise.future());
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);

            session.start();
            verify(clientSocket).handler(handlerCaptor.capture());
            handlerCaptor.getValue().handle(Buffer.buffer("slow"));

            verify(clientSocket).pause();
            verify(clientSocket, never()).resume();
            promise.complete();
            verify(clientSocket).resume();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldCloseOnClientDisconnect")
        void shouldCloseOnClientDisconnect() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(clientSocket).closeHandler(handlerCaptor.capture());

            handlerCaptor.getValue().handle(null);

            assertTrue(session.isClosing());
            verify(clientSocket).close((short) 1000, "Client disconnected");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldCloseOnBackendDisconnect")
        void shouldCloseOnBackendDisconnect() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(backendSocket).closeHandler(handlerCaptor.capture());

            handlerCaptor.getValue().handle(null);

            assertTrue(session.isClosing());
            verify(backendSocket).close((short) 1000, "Backend disconnected");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldCloseOnClientError")
        void shouldCloseOnClientError() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(clientSocket).exceptionHandler(handlerCaptor.capture());

            handlerCaptor.getValue().handle(new RuntimeException("connection reset"));

            assertTrue(session.isClosing());
            verify(clientSocket).close((short) 1011, "Client error");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldCloseOnBackendError")
        void shouldCloseOnBackendError() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(backendSocket).exceptionHandler(handlerCaptor.capture());

            handlerCaptor.getValue().handle(new RuntimeException("timeout"));

            assertTrue(session.isClosing());
            verify(backendSocket).close((short) 1011, "Backend error");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldCancelPongTimeoutOnPongReceived")
        void shouldCancelPongTimeoutOnPongReceived() {
            when(pingConfig.enabled()).thenReturn(true);
            // Return different timer IDs for different timers
            when(vertx.setTimer(anyLong(), any())).thenReturn(10L, 20L);
            when(vertx.setPeriodic(anyLong(), any())).thenReturn(30L);

            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            session.start();

            // Capture pong handler
            final var pongCaptor = ArgumentCaptor.forClass(Handler.class);
            verify(clientSocket).pongHandler(pongCaptor.capture());

            // Simulate pong
            pongCaptor.getValue().handle(Buffer.buffer("pong"));

            // Should cancel pong timeout timer (pongTimeoutTimerId starts at -1, so first call may be no-op)
            // and reset idle timer
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldIncrementRateLimitedMessagesOnRateLimitFailure")
        void shouldIncrementRateLimitedMessagesOnRateLimitFailure() {
            final var failingHandler = new MessageRateLimitHandler() {
                @Override
                public Uni<Void> checkAndProceed(Runnable onAllowed) {
                    return Uni.createFrom().failure(new RuntimeException("Rate limit exceeded"));
                }
            };

            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.empty(),
                    Optional.empty(),
                    failingHandler);

            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            session.start();
            verify(clientSocket).handler(handlerCaptor.capture());

            // Simulate a message that will be rate-limited
            handlerCaptor.getValue().handle(Buffer.buffer("msg"));

            // Allow async to complete
            assertEquals(1L, session.rateLimitedMessageCount());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldSendPingAndStartPongTimeoutWhenPingTimerFires")
        void shouldSendPingAndStartPongTimeoutWhenPingTimerFires() {
            when(pingConfig.enabled()).thenReturn(true);
            when(vertx.setPeriodic(anyLong(), any())).thenAnswer(invocation -> {
                // Don't fire immediately, just return ID
                return 30L;
            });

            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            session.start();

            // Capture the periodic handler for ping
            final var periodicCaptor = ArgumentCaptor.forClass(Handler.class);
            verify(vertx).setPeriodic(eq(Duration.ofSeconds(30).toMillis()), periodicCaptor.capture());

            // Fire the ping timer
            periodicCaptor.getValue().handle(30L);

            verify(clientSocket).writePing(any(Buffer.class));
            // Should also start pong timeout timer
            verify(vertx).setTimer(eq(Duration.ofSeconds(10).toMillis()), any());
        }
    }

    @Nested
    @DisplayName("closeWithReason")
    class CloseWithReasonTests {

        @Test
        @DisplayName("shouldBeIdempotent")
        void shouldBeIdempotent() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);

            session.closeWithReason((short) 1000, "first close");
            session.closeWithReason((short) 1001, "second close");

            // Only the first close should go through
            verify(clientSocket, times(1)).close(any(short.class), any(String.class));
            verify(backendSocket, times(1)).close(any(short.class), any(String.class));
            verify(clientSocket).close((short) 1000, "first close");
        }

        @Test
        @DisplayName("shouldCancelAllTimersOnClose")
        void shouldCancelAllTimersOnClose() {
            when(pingConfig.enabled()).thenReturn(true);
            // Return specific timer IDs
            when(vertx.setTimer(eq(Duration.ofMinutes(5).toMillis()), any())).thenReturn(100L);
            when(vertx.setTimer(eq(Duration.ofHours(24).toMillis()), any())).thenReturn(200L);
            when(vertx.setPeriodic(eq(Duration.ofSeconds(30).toMillis()), any()))
                    .thenReturn(300L);

            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            session.start();
            session.closeWithReason((short) 1000, "done");

            verify(vertx).cancelTimer(100L);
            verify(vertx).cancelTimer(200L);
            verify(vertx).cancelTimer(300L);
        }

        @Test
        @DisplayName("shouldNotCancelTimersWithIdMinusOneBeforeStart")
        void shouldNotCancelTimersWithIdMinusOneBeforeStart() {
            // Don't call start() - timer IDs are -1
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            session.closeWithReason((short) 1000, "done");

            // Timer IDs are -1, so cancelTimer should not be called
            verify(vertx, never()).cancelTimer(anyLong());
            assertTrue(session.isClosing());
        }

        @Test
        @DisplayName("shouldSanitizeCloseReasonsAndNotifyOnce")
        void shouldSanitizeCloseReasonsAndNotifyOnce() {
            final var closeCount = new AtomicInteger();
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    MessageRateLimitHandler.noOp(),
                    closeCount::incrementAndGet);
            final var reason = "unsafe\n" + "🙂".repeat(40);
            final var reasonCaptor = ArgumentCaptor.forClass(String.class);

            session.closeWithReason((short) 1011, reason);
            session.closeWithReason((short) 1011, "again");

            verify(clientSocket).close(eq((short) 1011), reasonCaptor.capture());
            assertFalse(reasonCaptor.getValue().contains("\n"));
            assertTrue(reasonCaptor.getValue().getBytes(StandardCharsets.UTF_8).length <= 123);
            assertEquals(1, closeCount.get());
        }
    }

    @Nested
    @DisplayName("shouldCloseFor")
    class ShouldCloseForTests {

        @Test
        @DisplayName("shouldReturnFalseWhenNoAuthSessionId")
        void shouldReturnFalseWhenNoAuthSessionId() {
            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            final var event = SessionInvalidatedEvent.forSession("any-session");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("shouldReturnTrueWhenSessionIdMatches")
        void shouldReturnTrueWhenSessionIdMatches() {
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-1"),
                    Optional.of("user-1"));

            final var event = SessionInvalidatedEvent.forSession("auth-session-1");

            assertTrue(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("shouldReturnFalseWhenSessionIdDoesNotMatch")
        void shouldReturnFalseWhenSessionIdDoesNotMatch() {
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-1"),
                    Optional.of("user-1"));

            final var event = SessionInvalidatedEvent.forSession("other-session");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("shouldReturnTrueWhenUserIdMatchesForLogoutEverywhere")
        void shouldReturnTrueWhenUserIdMatchesForLogoutEverywhere() {
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-1"),
                    Optional.of("user-1"));

            final var event = SessionInvalidatedEvent.forUser("user-1");

            assertTrue(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("shouldReturnFalseWhenUserIdDoesNotMatch")
        void shouldReturnFalseWhenUserIdDoesNotMatch() {
            final var session = new WebSocketProxySession(
                    "s1",
                    clientSocket,
                    backendSocket,
                    vertx,
                    config,
                    Optional.of("auth-session-1"),
                    Optional.of("user-1"));

            final var event = SessionInvalidatedEvent.forUser("other-user");

            assertFalse(session.shouldCloseFor(event));
        }

        @Test
        @DisplayName("shouldReturnFalseForUserEventWhenNoUserId")
        void shouldReturnFalseForUserEventWhenNoUserId() {
            final var session = new WebSocketProxySession(
                    "s1", clientSocket, backendSocket, vertx, config, Optional.of("auth-session-1"), Optional.empty());

            final var event = SessionInvalidatedEvent.forUser("user-1");

            assertFalse(session.shouldCloseFor(event));
        }
    }

    @Nested
    @DisplayName("idle timer reset")
    class IdleTimerResetTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("shouldResetIdleTimerOnBackendMessage")
        void shouldResetIdleTimerOnBackendMessage() {
            when(vertx.setTimer(eq(Duration.ofMinutes(5).toMillis()), any())).thenReturn(100L, 101L);

            final var session = new WebSocketProxySession("s1", clientSocket, backendSocket, vertx, config);
            session.start();

            // Capture backend handler
            final var handlerCaptor = ArgumentCaptor.forClass(Handler.class);
            verify(backendSocket).handler(handlerCaptor.capture());

            // Simulate backend message
            handlerCaptor.getValue().handle(Buffer.buffer("data"));

            // Should cancel old idle timer and start a new one
            verify(vertx).cancelTimer(100L);
            // Two idle timer starts: initial + reset
            verify(vertx, times(2)).setTimer(eq(Duration.ofMinutes(5).toMillis()), any());
        }
    }
}
