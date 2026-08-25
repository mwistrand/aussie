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
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketFrame;
import org.jboss.logging.Logger;

import aussie.core.config.WebSocketConfig;
import aussie.core.model.auth.RevocationEvent;
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
    private final Optional<String> tokenId;
    private final Optional<Instant> identityIssuedAt;
    private final Optional<Instant> identityExpiresAt;
    private final MessageRateLimitHandler messageRateLimitHandler;
    private final Runnable closeListener;

    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong rateLimitedMessages = new AtomicLong(0);
    private final FrameState clientFrames = new FrameState();
    private final FrameState backendFrames = new FrameState();
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
                Optional.empty(),
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
        this(
                sessionId,
                clientSocket,
                backendSocket,
                vertx,
                config,
                authSessionId,
                userId,
                Optional.empty(),
                Optional.empty(),
                identityExpiresAt,
                messageRateLimitHandler,
                closeListener);
    }

    public WebSocketProxySession(
            String sessionId,
            ServerWebSocket clientSocket,
            WebSocket backendSocket,
            Vertx vertx,
            WebSocketConfig config,
            Optional<String> authSessionId,
            Optional<String> userId,
            Optional<String> tokenId,
            Optional<Instant> identityIssuedAt,
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
        this.tokenId = tokenId;
        this.identityIssuedAt = identityIssuedAt;
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
        if (closing.get() || !started.compareAndSet(false, true)) {
            return;
        }
        if (config.maxQueueBytes() > 0) {
            clientSocket.setWriteQueueMaxSize(config.maxQueueBytes());
            backendSocket.setWriteQueueMaxSize(config.maxQueueBytes());
        }

        clientSocket.drainHandler(ignored -> resume(backendSocket));
        backendSocket.drainHandler(ignored -> resume(clientSocket));

        // Preserve frame type and fragmentation; never aggregate unbounded messages.
        clientSocket.frameHandler(frame -> handleFrame(clientSocket, backendSocket, clientFrames, frame, true));
        backendSocket.frameHandler(frame -> handleFrame(backendSocket, clientSocket, backendFrames, frame, false));

        // Handle close from either side, preserving valid peer close codes/reasons.
        clientSocket.closeHandler(v -> closeFrom(clientSocket, "Client disconnected"));
        backendSocket.closeHandler(v -> closeFrom(backendSocket, "Backend disconnected"));

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

    private void handleFrame(
            WebSocketBase source, WebSocketBase target, FrameState state, WebSocketFrame frame, boolean rateLimited) {
        if (closing.get() || !isDataFrame(frame)) {
            return;
        }

        if (!acceptFrame(state, frame)) {
            source.pause();
            return;
        }

        source.pause();
        final Runnable forward = () -> {
            resetIdleTimer();
            forward(source, target, frame);
        };
        if (!rateLimited || !isMessageStart(frame)) {
            forward.run();
            return;
        }

        messageRateLimitHandler.checkAndProceed(forward).subscribe().with(ignored -> {}, error -> {
            rateLimitedMessages.incrementAndGet();
            // 4429 mirrors HTTP 429 (Too Many Requests).
            closeWithReason((short) 4429, "Message rate limit exceeded");
        });
    }

    private boolean acceptFrame(FrameState state, WebSocketFrame frame) {
        if (frame.isContinuation() != state.fragmented) {
            closeWithReason((short) 1002, "Invalid frame sequence");
            return false;
        }
        final var bytes = frameBytes(frame);
        final var maxMessageBytes = config.maxMessageBytes();
        if (maxMessageBytes > 0 && (bytes > maxMessageBytes || state.bytes > maxMessageBytes - bytes)) {
            closeWithReason((short) 1009, "Message too large");
            return false;
        }
        state.bytes += bytes;
        state.fragmented = !frame.isFinal();
        if (frame.isFinal()) {
            state.bytes = 0;
        }
        return true;
    }

    private static boolean isDataFrame(WebSocketFrame frame) {
        return frame.isText() || frame.isBinary() || frame.isContinuation();
    }

    private static boolean isMessageStart(WebSocketFrame frame) {
        return frame.isText() || frame.isBinary();
    }

    private static int frameBytes(WebSocketFrame frame) {
        final var binary = frame.binaryData();
        return binary == null ? 0 : binary.length();
    }

    private void forward(WebSocketBase source, WebSocketBase target, WebSocketFrame frame) {
        if (closing.get()) {
            return;
        }
        final Future<Void> write;
        try {
            write = target.writeFrame(frame);
        } catch (RuntimeException failure) {
            closeWithReason((short) 1011, "Proxy write failed");
            return;
        }
        if (write == null) {
            if (!closing.get() && !target.writeQueueFull()) {
                resume(source);
            }
            return;
        }
        write.onComplete(result -> {
            if (result.failed()) {
                closeWithReason((short) 1011, "Proxy write failed");
            } else if (!closing.get() && !target.writeQueueFull()) {
                resume(source);
            }
        });
    }

    private void resume(WebSocketBase socket) {
        if (!closing.get()) {
            socket.resume();
        }
    }

    private void closeFrom(WebSocketBase socket, String fallback) {
        final var code = socket.closeStatusCode();
        final var reason = socket.closeReason();
        closeWithReason(
                code != null && isValidCloseCode(code) ? code : (short) 1000,
                reason == null || reason.isBlank() ? fallback : reason);
    }

    private static boolean isValidCloseCode(short code) {
        return (code >= 1000 && code <= 1014 && code != 1004 && code != 1005 && code != 1006)
                || (code >= 3000 && code <= 4999);
    }

    private void startIdleTimer() {
        var timeoutMs = config.idleTimeout().toMillis();
        idleTimerId = vertx.setTimer(timeoutMs, id -> closeWithReason((short) 1000, "Idle timeout exceeded"));
    }

    private void resetIdleTimer() {
        if (closing.get()) {
            return;
        }
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
                final var write = clientSocket.writePing(Buffer.buffer("ping"));
                if (write == null) {
                    startPongTimeout();
                } else {
                    write.onSuccess(ignored -> startPongTimeout())
                            .onFailure(error -> closeWithReason((short) 1011, "Heartbeat failed"));
                }
            }
        });
    }

    private void startPongTimeout() {
        if (closing.get()) {
            return;
        }
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
            idleTimerId = -1;
        }
        if (maxLifetimeTimerId != -1) {
            vertx.cancelTimer(maxLifetimeTimerId);
            maxLifetimeTimerId = -1;
        }
        if (identityExpiryTimerId != -1) {
            vertx.cancelTimer(identityExpiryTimerId);
            identityExpiryTimerId = -1;
        }
        if (pingTimerId != -1) {
            vertx.cancelTimer(pingTimerId);
            pingTimerId = -1;
        }
        if (pongTimeoutTimerId != -1) {
            vertx.cancelTimer(pongTimeoutTimerId);
            pongTimeoutTimerId = -1;
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
        var end = 123;
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
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

    /** Check whether a distributed revocation applies to this connection. */
    public boolean shouldCloseFor(RevocationEvent event) {
        final var now = Instant.now();
        if (event instanceof RevocationEvent.JtiRevoked revoked) {
            return revoked.expiresAt().isAfter(now)
                    && tokenId.filter(revoked.jti()::equals).isPresent();
        }
        if (event instanceof RevocationEvent.UserRevoked revoked) {
            return revoked.expiresAt().isAfter(now)
                    && userId.filter(revoked.userId()::equals).isPresent()
                    && identityIssuedAt
                            .map(issuedAt -> issuedAt.isBefore(revoked.issuedBefore()))
                            .orElse(true);
        }
        return false;
    }

    /**
     * Get the count of rate-limited messages for this session.
     *
     * @return rate limited message count
     */
    public long rateLimitedMessageCount() {
        return rateLimitedMessages.get();
    }

    private static final class FrameState {
        private int bytes;
        private boolean fragmented;
    }
}
