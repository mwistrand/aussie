package aussie.core.model.websocket;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.jboss.logging.Logger;

import aussie.core.config.WebSocketConfig;
import aussie.core.model.ratelimit.MessageRateLimitHandler;
import aussie.core.model.session.SessionInvalidatedEvent;

/**
 * Manages the coupled lifecycle of a WebSocket proxy session.
 *
 * <p>Aussie maintains two separate WebSocket connections:
 * <ul>
 *   <li>Connection A: Client to Aussie</li>
 *   <li>Connection B: Aussie to Backend</li>
 * </ul>
 *
 * <p>When either connection closes (gracefully or due to error/timeout),
 * the other connection is also closed with a reason message.
 *
 * <p>All operations are non-blocking and run on the Vert.x event loop.
 */
public class WebSocketProxySession {

    private static final Logger LOG = Logger.getLogger(WebSocketProxySession.class);

    private final String sessionId;
    private final ServerWebSocket clientSocket;
    private final WebSocket backendSocket;
    private final Vertx vertx;
    private final WebSocketConfig config;
    private final Optional<String> authSessionId;
    private final Optional<String> userId;
    private final Optional<Instant> identityExpiresAt;
    private final MessageRateLimitHandler messageRateLimitHandler;
    private final Runnable closeListener;

    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong rateLimitedMessages = new AtomicLong(0);
    private long idleTimerId = -1;
    private long maxLifetimeTimerId = -1;
    private long identityExpiryTimerId = -1;
    private long pingTimerId = -1;
    private long pongTimeoutTimerId = -1;
    private final Instant connectedAt;
    private volatile Instant lastActivity;

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfig config) {
        this(
                sessionId,
                clientSocket,
                backendSocket,
                vertx,
                config,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                MessageRateLimitHandler.noOp(),
                () -> {});
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfig config,
            Optional<String> authSessionId,
            Optional<String> userId) {
        this(
                sessionId,
                clientSocket,
                backendSocket,
                vertx,
                config,
                authSessionId,
                userId,
                Optional.empty(),
                MessageRateLimitHandler.noOp(),
                () -> {});
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfig config,
            Optional<String> authSessionId,
            Optional<String> userId,
            MessageRateLimitHandler messageRateLimitHandler) {
        this(
                sessionId,
                clientSocket,
                backendSocket,
                vertx,
                config,
                authSessionId,
                userId,
                Optional.empty(),
                messageRateLimitHandler,
                () -> {});
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfig config,
            Optional<String> authSessionId,
            Optional<String> userId,
            Optional<Instant> identityExpiresAt,
            MessageRateLimitHandler messageRateLimitHandler,
            Runnable closeListener) {
        this.sessionId = sessionId;
        this.clientSocket = clientSocket;
        this.backendSocket = backendSocket;
        this.vertx = vertx;
        this.config = config;
        this.authSessionId = authSessionId;
        this.userId = userId;
        this.identityExpiresAt = identityExpiresAt;
        this.messageRateLimitHandler = messageRateLimitHandler;
        this.closeListener = closeListener;
        this.connectedAt = Instant.now();
        this.lastActivity = Instant.now();
    }

    /**
     * Start the proxy session.
     *
     * <p>Enables bidirectional message forwarding and starts lifecycle timers.
     */
    public void start() {
        if (config.maxQueueBytes() > 0) {
            clientSocket.setWriteQueueMaxSize(config.maxQueueBytes());
            backendSocket.setWriteQueueMaxSize(config.maxQueueBytes());
        }

        // Set up bidirectional message forwarding with rate limiting (non-blocking)
        clientSocket.handler(buffer -> {
            clientSocket.pause();
            messageRateLimitHandler
                    .checkAndProceed(() -> {
                        resetIdleTimer();
                        forward(clientSocket, backendSocket, buffer);
                    })
                    .subscribe()
                    .with(
                            v -> {
                                /* success, message forwarded */
                            },
                            err -> {
                                rateLimitedMessages.incrementAndGet();
                                // 4429 mirrors HTTP 429 (Too Many Requests)
                                closeWithReason((short) 4429, "Message rate limit exceeded");
                            });
        });

        backendSocket.handler(buffer -> {
            backendSocket.pause();
            resetIdleTimer();
            forward(backendSocket, clientSocket, buffer);
        });

        // Handle close from either side
        clientSocket.closeHandler(v -> closeWithReason((short) 1000, "Client disconnected"));
        backendSocket.closeHandler(v -> closeWithReason((short) 1000, "Backend disconnected"));

        // Handle errors
        clientSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Client error"));
        backendSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Backend error"));

        // Handle pong responses from client
        clientSocket.pongHandler(buffer -> {
            cancelPongTimeout();
            resetIdleTimer();
        });

        // Start timers (all timer callbacks run on event loop, non-blocking)
        startIdleTimer();
        startMaxLifetimeTimer();
        startIdentityExpiryTimer();
        if (config.ping().enabled()) {
            startPingTimer();
        }
    }

    private void forward(ReadStream<Buffer> source, WriteStream<Buffer> target, Buffer buffer) {
        if (closing.get()) {
            return;
        }
        target.drainHandler(ignored -> {
            if (!closing.get()) {
                source.resume();
            }
        });
        final Future<Void> write;
        try {
            write = target.write(buffer);
        } catch (RuntimeException failure) {
            closeWithReason((short) 1011, "Proxy write failed");
            return;
        }
        if (write == null) {
            source.resume();
            return;
        }
        write.onComplete(result -> {
            if (result.failed()) {
                closeWithReason((short) 1011, "Proxy write failed");
            } else if (!target.writeQueueFull() && !closing.get()) {
                source.resume();
            }
        });
    }

    private void startIdleTimer() {
        var timeoutMs = config.idleTimeout().toMillis();
        idleTimerId = vertx.setTimer(timeoutMs, id -> closeWithReason((short) 1000, "Idle timeout exceeded"));
    }

    private void resetIdleTimer() {
        lastActivity = Instant.now();
        if (idleTimerId != -1) {
            vertx.cancelTimer(idleTimerId);
        }
        startIdleTimer();
    }

    private void startMaxLifetimeTimer() {
        var lifetimeMs = config.maxLifetime().toMillis();
        maxLifetimeTimerId =
                vertx.setTimer(lifetimeMs, id -> closeWithReason((short) 1000, "Maximum connection lifetime exceeded"));
    }

    private void startIdentityExpiryTimer() {
        identityExpiresAt.ifPresent(expiresAt -> {
            final var delayMs =
                    Math.max(1, Duration.between(Instant.now(), expiresAt).toMillis());
            identityExpiryTimerId =
                    vertx.setTimer(delayMs, id -> closeWithReason((short) 1008, "Authentication expired"));
        });
    }

    private void startPingTimer() {
        var intervalMs = config.ping().interval().toMillis();
        pingTimerId = vertx.setPeriodic(intervalMs, id -> {
            if (!closing.get()) {
                clientSocket.writePing(Buffer.buffer("ping"));
                startPongTimeout();
            }
        });
    }

    private void startPongTimeout() {
        cancelPongTimeout();
        var timeoutMs = config.ping().timeout().toMillis();
        pongTimeoutTimerId =
                vertx.setTimer(timeoutMs, id -> closeWithReason((short) 1002, "Ping timeout - no pong received"));
    }

    private void cancelPongTimeout() {
        if (pongTimeoutTimerId != -1) {
            vertx.cancelTimer(pongTimeoutTimerId);
            pongTimeoutTimerId = -1;
        }
    }

    /**
     * Close both connections with a reason.
     *
     * <p>This method is idempotent - multiple calls have no effect after the first.
     *
     * @param code   WebSocket close code
     * @param reason Close reason message
     */
    public void closeWithReason(short code, String reason) {
        if (!closing.compareAndSet(false, true)) {
            return; // Already closing
        }

        final var safeReason = safeCloseReason(reason);

        // Cancel all timers
        if (idleTimerId != -1) {
            vertx.cancelTimer(idleTimerId);
        }
        if (maxLifetimeTimerId != -1) {
            vertx.cancelTimer(maxLifetimeTimerId);
        }
        if (identityExpiryTimerId != -1) {
            vertx.cancelTimer(identityExpiryTimerId);
        }
        if (pingTimerId != -1) {
            vertx.cancelTimer(pingTimerId);
        }
        if (pongTimeoutTimerId != -1) {
            vertx.cancelTimer(pongTimeoutTimerId);
        }

        // Close both connections with the reason (non-blocking)
        clientSocket.close(code, safeReason);
        backendSocket.close(code, safeReason);

        // Log session end (for metrics)
        var duration = Duration.between(connectedAt, Instant.now()).toSeconds();
        LOG.infov("WebSocket session {0} closed: {1} (duration: {2}s)", sessionId, safeReason, duration);
        closeListener.run();
    }

    private static String safeCloseReason(String reason) {
        final var value = reason == null ? "Connection closed" : reason.replaceAll("\\p{Cc}", " ");
        final var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 123) {
            return value;
        }
        for (var end = 123; end > 0; end--) {
            final var candidate = new String(bytes, 0, end, StandardCharsets.UTF_8);
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= 123) {
                return candidate;
            }
        }
        return "";
    }

    public String sessionId() {
        return sessionId;
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public boolean isClosing() {
        return closing.get();
    }

    public Optional<String> authSessionId() {
        return authSessionId;
    }

    public Optional<String> userId() {
        return userId;
    }

    /**
     * Check if this session should be closed due to a session invalidation event.
     *
     * @param event the session invalidation event
     * @return true if this session should be closed
     */
    public boolean shouldCloseFor(SessionInvalidatedEvent event) {
        // Only authenticated sessions can be affected by logout
        if (authSessionId.isEmpty()) {
            return false;
        }
        return event.appliesTo(authSessionId.get(), userId.orElse(null));
    }

    /**
     * Get the count of rate-limited messages for this session.
     *
     * @return rate limited message count
     */
    public long rateLimitedMessageCount() {
        return rateLimitedMessages.get();
    }
}
