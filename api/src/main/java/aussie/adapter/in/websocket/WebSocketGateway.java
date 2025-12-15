package aussie.adapter.in.websocket;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.ratelimit.MessageRateLimitHandler;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.model.websocket.WebSocketProxySession;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.service.ratelimit.WebSocketRateLimitService;

/**
 * Handle WebSocket proxy connections after authentication.
 *
 * <p>All operations are fully reactive using Vert.x Futures - no blocking calls.
 *
 * <p>Manages two separate WebSocket connections per session:
 * <ul>
 *   <li>Client connection (A): Browser/client to Aussie</li>
 *   <li>Backend connection (B): Aussie to backend service</li>
 * </ul>
 */
@ApplicationScoped
public class WebSocketGateway {

    private static final Logger LOG = Logger.getLogger(WebSocketGateway.class);

    // Track active sessions for metrics/debugging (per-instance count)
    private final Map<String, WebSocketProxySession> activeSessions = new ConcurrentHashMap<>();

    private final WebSocketGatewayUseCase gatewayUseCase;
    private final WebSocketConfig config;
    private final Vertx vertx;
    private final GatewayMetrics metrics;
    private final WebSocketRateLimitService rateLimitService;

    @Inject
    public WebSocketGateway(
            WebSocketGatewayUseCase gatewayUseCase,
            WebSocketConfig config,
            Vertx vertx,
            GatewayMetrics metrics,
            WebSocketRateLimitService rateLimitService) {
        this.gatewayUseCase = gatewayUseCase;
        this.config = config;
        this.vertx = vertx;
        this.metrics = metrics;
        this.rateLimitService = rateLimitService;
    }

    /**
     * Handle WebSocket upgrade for gateway mode (/gateway/...).
     */
    public void handleGatewayUpgrade(RoutingContext ctx) {
        var path = ctx.request().path().substring("/gateway".length());
        if (path.isEmpty()) {
            path = "/";
        }
        handleUpgrade(ctx, gatewayUseCase.upgradeGateway(buildRequest(path, ctx)));
    }

    /**
     * Handle WebSocket upgrade for pass-through mode (/{serviceId}/...).
     */
    public void handlePassThroughUpgrade(RoutingContext ctx) {
        var fullPath = ctx.request().path();
        var slashIndex = fullPath.indexOf('/', 1);
        var serviceId = fullPath.substring(1, slashIndex > 0 ? slashIndex : fullPath.length());
        var path = slashIndex > 0 ? fullPath.substring(slashIndex) : "/";

        handleUpgrade(ctx, gatewayUseCase.upgradePassThrough(serviceId, buildRequest(path, ctx)));
    }

    private void handleUpgrade(RoutingContext ctx, Uni<WebSocketUpgradeResult> resultUni) {
        // Check connection limit before processing
        if (activeSessions.size() >= config.maxConnections()) {
            LOG.warnv("WebSocket connection limit reached ({0})", config.maxConnections());
            ctx.response().setStatusCode(503).end("Service temporarily unavailable: connection limit reached");
            return;
        }

        // Subscribe on Vert.x context to ensure callbacks run on event loop
        resultUni
                .subscribe()
                .with(
                        result -> {
                            switch (result) {
                                case WebSocketUpgradeResult.Authorized auth -> establishProxy(ctx, auth);
                                case WebSocketUpgradeResult.Unauthorized u -> ctx.response()
                                        .setStatusCode(401)
                                        .end(u.reason());
                                case WebSocketUpgradeResult.Forbidden f -> ctx.response()
                                        .setStatusCode(403)
                                        .end(f.reason());
                                case WebSocketUpgradeResult.RouteNotFound r -> ctx.response()
                                        .setStatusCode(404)
                                        .end("Route not found: " + r.path());
                                case WebSocketUpgradeResult.ServiceNotFound s -> ctx.response()
                                        .setStatusCode(404)
                                        .end("Service not found: " + s.serviceId());
                                case WebSocketUpgradeResult.NotWebSocket n -> ctx.response()
                                        .setStatusCode(400)
                                        .end("Not a WebSocket endpoint: " + n.path());
                                case WebSocketUpgradeResult.RateLimited rl -> handleRateLimited(ctx, rl);
                            }
                        },
                        error -> {
                            LOG.errorv(error, "WebSocket upgrade failed");
                            ctx.response().setStatusCode(500).end("Internal error");
                        });
    }

    private void handleRateLimited(RoutingContext ctx, WebSocketUpgradeResult.RateLimited rl) {
        metrics.recordRateLimitExceeded("unknown", "ws_connection");
        ctx.response()
                .setStatusCode(429)
                .putHeader("Retry-After", String.valueOf(rl.retryAfterSeconds()))
                .putHeader("X-RateLimit-Limit", String.valueOf(rl.limit()))
                .putHeader("X-RateLimit-Remaining", "0")
                .putHeader("X-RateLimit-Reset", String.valueOf(rl.resetAtEpochSeconds()))
                .end("Rate limit exceeded. Retry after " + rl.retryAfterSeconds() + " seconds.");
    }

    private void establishProxy(RoutingContext ctx, WebSocketUpgradeResult.Authorized auth) {
        final var sessionId = UUID.randomUUID().toString();
        final var serviceId = auth.route().service().serviceId();
        final var clientId = extractClientId(ctx);

        // Extract auth session ID and user ID for logout tracking
        final var authSessionId = auth.authSessionId();
        final var userId = auth.token().map(t -> t.subject());

        // Build headers for backend connection
        final var headers = MultiMap.caseInsensitiveMultiMap();
        if (auth.token().isPresent()) {
            headers.add("Authorization", "Bearer " + auth.token().get().jws());
        }

        // Connect to backend WebSocket FIRST (non-blocking Future)
        final var backendUri = auth.backendUri();
        final var options = new WebSocketConnectOptions()
                .setHost(backendUri.getHost())
                .setPort(getPort(backendUri))
                .setURI(backendUri.getPath())
                .setHeaders(headers)
                .setSsl("wss".equals(backendUri.getScheme()));

        vertx.createHttpClient()
                .webSocket(options)
                .onSuccess(backendWs -> {
                    // Backend connected - now upgrade client connection (non-blocking)
                    ctx.request()
                            .toWebSocket()
                            .onSuccess(clientWs -> {
                                // Create message rate limit handler
                                final var messageHandler =
                                        createMessageRateLimitHandler(serviceId, clientId, sessionId);

                                // Both connections established - create proxy session
                                final var session = new WebSocketProxySession(
                                        sessionId,
                                        clientWs,
                                        backendWs,
                                        vertx,
                                        config,
                                        authSessionId,
                                        userId,
                                        messageHandler);

                                activeSessions.put(sessionId, session);

                                // Track connection metrics
                                metrics.incrementActiveWebSockets();
                                metrics.recordWebSocketConnect(serviceId);

                                // Clean up session when closed
                                clientWs.closeHandler(v -> {
                                    activeSessions.remove(sessionId);
                                    metrics.decrementActiveWebSockets();
                                    rateLimitService
                                            .cleanupConnection(serviceId, clientId, sessionId)
                                            .subscribe()
                                            .with(
                                                    ignored -> {},
                                                    err -> LOG.warnv(
                                                            err,
                                                            "Failed to cleanup rate limit state for session {0}",
                                                            sessionId));
                                });

                                // Start the session (enables message forwarding and timers)
                                session.start();

                                LOG.infov("WebSocket session {0} established to {1}", sessionId, backendUri);
                            })
                            .onFailure(err -> {
                                LOG.warnv(err, "Client WebSocket upgrade failed for session {0}", sessionId);
                                backendWs.close((short) 1001, "Client upgrade failed");
                                ctx.response().setStatusCode(500).end("WebSocket upgrade failed");
                            });
                })
                .onFailure(err -> {
                    LOG.warnv(err, "Backend WebSocket connection failed to {0}", backendUri);
                    // Don't expose internal error details to clients for security reasons
                    ctx.response().setStatusCode(502).end("Backend connection failed");
                });
    }

    private MessageRateLimitHandler createMessageRateLimitHandler(String serviceId, String clientId, String sessionId) {
        if (!rateLimitService.isMessageRateLimitEnabled()) {
            return MessageRateLimitHandler.noOp();
        }

        return onAllowed -> rateLimitService
                .checkMessageLimit(serviceId, clientId, sessionId)
                .map(decision -> {
                    if (decision.allowed()) {
                        onAllowed.run();
                    } else {
                        metrics.recordRateLimitExceeded(serviceId, "ws_message");
                        throw new RuntimeException("Message rate limit exceeded");
                    }
                    return null;
                })
                .replaceWithVoid();
    }

    private String extractClientId(RoutingContext ctx) {
        // Priority: session cookie > auth header > API key header > IP
        final var sessionCookie = ctx.request().getCookie("aussie_session");
        if (sessionCookie != null) {
            return "session:" + sessionCookie.getValue();
        }

        final var sessionHeader = ctx.request().getHeader("X-Session-ID");
        if (sessionHeader != null) {
            return "session:" + sessionHeader;
        }

        final var authHeader = ctx.request().getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return "bearer:" + Integer.toHexString(authHeader.substring(7).hashCode());
        }

        final var apiKeyId = ctx.request().getHeader("X-API-Key-ID");
        if (apiKeyId != null) {
            return "apikey:" + apiKeyId;
        }

        final var forwarded = ctx.request().getHeader("X-Forwarded-For");
        if (forwarded != null) {
            return "ip:" + forwarded.split(",")[0].trim();
        }

        return "ip:unknown";
    }

    /**
     * Handle session invalidation events (logout).
     *
     * <p>When a user logs out, all their WebSocket connections must be closed.
     *
     * @param event the session invalidation event
     */
    void onSessionInvalidated(@ObservesAsync SessionInvalidatedEvent event) {
        var sessionsToClose = activeSessions.values().stream()
                .filter(session -> session.shouldCloseFor(event))
                .toList();

        if (!sessionsToClose.isEmpty()) {
            LOG.infov("Closing {0} WebSocket session(s) due to logout", sessionsToClose.size());
            sessionsToClose.forEach(session -> session.closeWithReason((short) 1000, "Session logged out"));
        }
    }

    private int getPort(URI uri) {
        var port = uri.getPort();
        if (port != -1) {
            return port;
        }
        // Default ports based on scheme
        return "wss".equals(uri.getScheme()) ? 443 : 80;
    }

    private WebSocketUpgradeRequest buildRequest(String path, RoutingContext ctx) {
        var headers = new HashMap<String, List<String>>();
        ctx.request().headers().forEach(entry -> headers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                .add(entry.getValue()));

        return new WebSocketUpgradeRequest(
                path, headers, URI.create(ctx.request().absoluteURI()));
    }

    /**
     * Get the number of active WebSocket sessions (per-instance).
     *
     * @return active session count
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
