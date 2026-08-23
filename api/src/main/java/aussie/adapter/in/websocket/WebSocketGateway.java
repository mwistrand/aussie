package aussie.adapter.in.websocket;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.auth.OidcTokenValidator.TokenParseException;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.ratelimit.MessageRateLimitHandler;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.model.websocket.WebSocketProxySession;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.auth.JwksCacheService.JwksFetchException;
import aussie.core.service.ratelimit.WebSocketRateLimitService;
import aussie.core.service.routing.UpstreamAddressResolver;
import aussie.core.util.SecureHash;

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
    private final HttpClient httpClient;
    private final GatewayMetrics metrics;
    private final WebSocketRateLimitService rateLimitService;
    private final ProxyErrorWriter errorWriter;
    private final ClientContextResolver clientContextResolver;
    private final UpstreamAddressResolver addressResolver;

    @Inject
    public WebSocketGateway(
            WebSocketGatewayUseCase gatewayUseCase,
            WebSocketConfig config,
            Vertx vertx,
            OutboundHttpClients outboundClient,
            GatewayMetrics metrics,
            WebSocketRateLimitService rateLimitService,
            ProxyErrorWriter errorWriter,
            ClientContextResolver clientContextResolver,
            UpstreamAddressResolver addressResolver) {
        this.gatewayUseCase = gatewayUseCase;
        this.config = config;
        this.vertx = vertx;
        this.httpClient = outboundClient.httpClient();
        this.metrics = metrics;
        this.rateLimitService = rateLimitService;
        this.errorWriter = errorWriter;
        this.clientContextResolver = clientContextResolver;
        this.addressResolver = addressResolver;
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
        if (activeSessions.size() >= config.maxConnections()) {
            LOG.warnv("WebSocket connection limit reached ({0})", config.maxConnections());
            errorWriter.write(
                    ctx, ProblemDetail.serviceUnavailable("Service temporarily unavailable: connection limit reached"));
            return;
        }

        // Subscribe on Vert.x context to ensure callbacks run on event loop
        resultUni
                .subscribe()
                .with(
                        result -> {
                            switch (result) {
                                case WebSocketUpgradeResult.Authorized auth -> establishProxy(ctx, auth);
                                case WebSocketUpgradeResult.Unauthorized u -> errorWriter.write(
                                        ctx, ProblemDetail.unauthorized(u.reason()));
                                case WebSocketUpgradeResult.Forbidden f -> errorWriter.write(
                                        ctx, ProblemDetail.forbidden(f.reason()));
                                case WebSocketUpgradeResult.RouteNotFound r -> errorWriter.write(
                                        ctx, ProblemDetail.routeNotFound(r.path()));
                                case WebSocketUpgradeResult.ServiceNotFound s -> errorWriter.write(
                                        ctx, ProblemDetail.serviceNotFound(s.serviceId()));
                                case WebSocketUpgradeResult.NotWebSocket n -> errorWriter.write(
                                        ctx, ProblemDetail.badRequest("Not a WebSocket endpoint: " + n.path()));
                                case WebSocketUpgradeResult.RateLimited rl -> handleRateLimited(ctx, rl);
                            }
                        },
                        error -> {
                            int statusCode = mapErrorToStatusCode(error);
                            String message = mapErrorToMessage(statusCode);
                            LOG.warnv(error, "WebSocket upgrade failed with status {0}: {1}", statusCode, message);
                            errorWriter.write(ctx, problemFor(statusCode, message));
                        });
    }

    private void handleRateLimited(RoutingContext ctx, WebSocketUpgradeResult.RateLimited rl) {
        metrics.recordRateLimitExceeded(null, "ws_connection");
        final var problem = ProblemDetail.tooManyRequests(
                "Rate limit exceeded. Retry after " + rl.retryAfterSeconds() + " seconds.",
                rl.retryAfterSeconds(),
                rl.limit(),
                0,
                rl.resetAtEpochSeconds());
        errorWriter.writeRateLimit(ctx, problem, null, rl.retryAfterSeconds(), rl.limit(), rl.resetAtEpochSeconds());
    }

    private ProblemDetail problemFor(int statusCode, String message) {
        return switch (statusCode) {
            case 400 -> ProblemDetail.badRequest(message);
            case 502 -> ProblemDetail.badGateway(message);
            default -> ProblemDetail.internalError(message);
        };
    }

    @SuppressWarnings("deprecation")
    private void establishProxy(RoutingContext ctx, WebSocketUpgradeResult.Authorized auth) {
        final var sessionId = UUID.randomUUID().toString();
        final var serviceId = auth.route().service().serviceId();
        final var clientId = auth.token()
                .map(token -> {
                    clientContextResolver.attachVerifiedIdentity(
                            ctx,
                            token.subject(),
                            auth.authSessionId()
                                    .orElseGet(() ->
                                            Objects.toString(token.claims().get("jti"), token.subject())));
                    return "principal:" + SecureHash.truncatedSha256(token.subject(), 16);
                })
                .orElseGet(() -> "ip:" + clientContextResolver.getOrCompute(ctx).resolvedIp());

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

        addressResolver
                .resolve(backendUri)
                .subscribe()
                .with(
                        serverAddress -> {
                            options.setServer(serverAddress);
                            httpClient
                                    .webSocket(options)
                                    .onSuccess(backendWs -> {
                                        // Backend connected - now upgrade client connection (non-blocking)
                                        ctx.request()
                                                .toWebSocket()
                                                .onSuccess(clientWs -> {
                                                    // Create message rate limit handler
                                                    final var messageHandler = createMessageRateLimitHandler(
                                                            serviceId, clientId, sessionId);

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

                                                    LOG.infov(
                                                            "WebSocket session {0} established to {1}",
                                                            sessionId, backendUri);
                                                })
                                                .onFailure(err -> {
                                                    LOG.warnv(
                                                            err,
                                                            "Client WebSocket upgrade failed for session {0}",
                                                            sessionId);
                                                    backendWs.close((short) 1001, "Client upgrade failed");
                                                    errorWriter.write(
                                                            ctx,
                                                            ProblemDetail.internalError("WebSocket upgrade failed"),
                                                            serviceId);
                                                });
                                    })
                                    .onFailure(err -> {
                                        LOG.warnv(err, "Backend WebSocket connection failed to {0}", backendUri);
                                        errorWriter.write(
                                                ctx, ProblemDetail.badGateway("Backend connection failed"), serviceId);
                                    });
                        },
                        err -> {
                            LOG.warnv(err, "Backend WebSocket address denied for service {0}", serviceId);
                            errorWriter.write(ctx, ProblemDetail.badGateway("Backend connection failed"), serviceId);
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

    private int mapErrorToStatusCode(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof JwksFetchException || cause instanceof TokenParseException) {
                return 502;
            }
            if (cause instanceof IllegalArgumentException) {
                return 400;
            }
            cause = cause.getCause();
        }
        return 500;
    }

    private String mapErrorToMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad request";
            case 502 -> "Identity provider unavailable";
            default -> "Internal error";
        };
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

        var clientIp = clientContextResolver.getOrCompute(ctx).resolvedIp();

        return new WebSocketUpgradeRequest(
                path, headers, URI.create(ctx.request().absoluteURI()), clientIp);
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
