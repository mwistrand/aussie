package aussie.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import org.jboss.logging.Logger;

import aussie.config.WebSocketConfigMapping;

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
    private final WebSocketConfigMapping config;
    private final Optional<String> authSessionId;
    private final Optional<String> userId;
    private final MessageRateLimitHandler messageRateLimitHandler;

    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong rateLimitedMessages = new AtomicLong(0);
    private long idleTimerId = -1;
    private long maxLifetimeTimerId = -1;
    private long pingTimerId = -1;
    private long pongTimeoutTimerId = -1;
    private final Instant connectedAt;
    private volatile Instant lastActivity;

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfigMapping config) {
        this(
                sessionId,
                clientSocket,
                backendSocket,
                vertx,
                config,
                Optional.empty(),
                Optional.empty(),
                MessageRateLimitHandler.noOp());
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfigMapping config,
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
                MessageRateLimitHandler.noOp());
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfigMapping config,
            Optional<String> authSessionId,
            Optional<String> userId,
            MessageRateLimitHandler messageRateLimitHandler) {
        this.sessionId = sessionId;
        this.clientSocket = clientSocket;
        this.backendSocket = backendSocket;
        this.vertx = vertx;
        this.config = config;
        this.authSessionId = authSessionId;
        this.userId = userId;
        this.messageRateLimitHandler = messageRateLimitHandler;
        this.connectedAt = Instant.now();
        this.lastActivity = Instant.now();
    }

    /**
     * Start the proxy session.
     *
     * <p>Enables bidirectional message forwarding and starts lifecycle timers.
     */
    public void start() {
        // Set up bidirectional message forwarding with rate limiting (non-blocking)
        clientSocket.handler(buffer -> {
            messageRateLimitHandler
                    .checkAndProceed(() -> {
                        resetIdleTimer();
                        backendSocket.write(buffer); // Returns Future, non-blocking
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
            resetIdleTimer();
            clientSocket.write(buffer); // Returns Future, non-blocking
        });

        // Handle close from either side
        clientSocket.closeHandler(v -> closeWithReason((short) 1000, "Client disconnected"));
        backendSocket.closeHandler(v -> closeWithReason((short) 1000, "Backend disconnected"));

        // Handle errors
        clientSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Client error: " + t.getMessage()));
        backendSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Backend error: " + t.getMessage()));

        // Handle pong responses from client
        clientSocket.pongHandler(buffer -> {
            cancelPongTimeout();
            resetIdleTimer();
        });

        // Start timers (all timer callbacks run on event loop, non-blocking)
        startIdleTimer();
        startMaxLifetimeTimer();
        if (config.ping().enabled()) {
            startPingTimer();
        }
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

        // Cancel all timers
        if (idleTimerId != -1) {
            vertx.cancelTimer(idleTimerId);
        }
        if (maxLifetimeTimerId != -1) {
            vertx.cancelTimer(maxLifetimeTimerId);
        }
        if (pingTimerId != -1) {
            vertx.cancelTimer(pingTimerId);
        }
        if (pongTimeoutTimerId != -1) {
            vertx.cancelTimer(pongTimeoutTimerId);
        }

        // Close both connections with the reason (non-blocking)
        clientSocket.close(code, reason);
        backendSocket.close(code, reason);

        // Log session end (for metrics)
        var duration = Duration.between(connectedAt, Instant.now()).toSeconds();
        LOG.infov("WebSocket session {0} closed: {1} (duration: {2}s)", sessionId, reason, duration);
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
