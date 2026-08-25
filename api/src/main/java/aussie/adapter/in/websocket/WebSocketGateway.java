package aussie.adapter.in.websocket;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.http.GatewayCorsConfig;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.auth.OidcTokenValidator.TokenParseException;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.core.config.WebSocketConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.RevocationEvent;
import aussie.core.model.ratelimit.MessageRateLimitHandler;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.model.websocket.WebSocketProxySession;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.port.in.WebSocketGatewayUseCase;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.port.out.RevocationEventPublisher;
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
    private final AtomicInteger connectionReservations = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();

    private final WebSocketGatewayUseCase gatewayUseCase;
    private final WebSocketConfig config;
    private final Vertx vertx;
    private final HttpClient httpClient;
    private final GatewayMetrics metrics;
    private final WebSocketRateLimitService rateLimitService;
    private final ProxyErrorWriter errorWriter;
    private final ClientContextResolver clientContextResolver;
    private final UpstreamAddressResolver addressResolver;
    private final GatewayCorsConfig globalCorsConfig;
    private final RevocationEventPublisher revocationEventPublisher;
    private Cancellable revocationSubscription;

    public WebSocketGateway(
            WebSocketGatewayUseCase gatewayUseCase,
            WebSocketConfig config,
            Vertx vertx,
            OutboundHttpClients outboundClient,
            GatewayMetrics metrics,
            WebSocketRateLimitService rateLimitService,
            ProxyErrorWriter errorWriter,
            ClientContextResolver clientContextResolver,
            UpstreamAddressResolver addressResolver,
            GatewayCorsConfig globalCorsConfig) {
        this(
                gatewayUseCase,
                config,
                vertx,
                outboundClient,
                metrics,
                rateLimitService,
                errorWriter,
                clientContextResolver,
                addressResolver,
                globalCorsConfig,
                null);
    }

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
            UpstreamAddressResolver addressResolver,
            GatewayCorsConfig globalCorsConfig,
            RevocationEventPublisher revocationEventPublisher) {
        this.gatewayUseCase = gatewayUseCase;
        this.config = config;
        this.vertx = vertx;
        this.httpClient = outboundClient.httpClient();
        this.metrics = metrics;
        this.rateLimitService = rateLimitService;
        this.errorWriter = errorWriter;
        this.clientContextResolver = clientContextResolver;
        this.addressResolver = addressResolver;
        this.globalCorsConfig = globalCorsConfig;
        this.revocationEventPublisher = revocationEventPublisher;
    }

    @PostConstruct
    void subscribeToRevocations() {
        if (revocationEventPublisher != null) {
            revocationSubscription = revocationEventPublisher
                    .subscribe()
                    .subscribe()
                    .with(this::onRevocation, error -> LOG.warnv(error, "WebSocket revocation subscription stopped"));
        }
    }

    @PreDestroy
    void stopRevocationSubscription() {
        if (revocationSubscription != null) {
            revocationSubscription.cancel();
            revocationSubscription = null;
        }
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
        if (!reserveConnection()) {
            if (draining.get()) {
                errorWriter.write(ctx, ProblemDetail.serviceUnavailable("Gateway is shutting down"));
            } else {
                LOG.warnv("WebSocket connection limit reached ({0})", config.maxConnections());
                errorWriter.write(
                        ctx,
                        ProblemDetail.serviceUnavailable("Service temporarily unavailable: connection limit reached"));
            }
            return;
        }

        // Subscribe on Vert.x context to ensure callbacks run on event loop
        resultUni
                .subscribe()
                .with(
                        result -> {
                            switch (result) {
                                case WebSocketUpgradeResult.Authorized auth -> {
                                    final var protocol = selectSubprotocol(ctx);
                                    if (protocol.isEmpty() && hasRequestedSubprotocol(ctx)) {
                                        releaseConnection();
                                        errorWriter.write(
                                                ctx, ProblemDetail.badRequest("WebSocket subprotocol not allowed"));
                                    } else if (draining.get()) {
                                        releaseConnection();
                                        errorWriter.write(
                                                ctx, ProblemDetail.serviceUnavailable("Gateway is shutting down"));
                                    } else if (!isOriginAllowed(ctx, auth)) {
                                        releaseConnection();
                                        errorWriter.write(ctx, ProblemDetail.forbidden("WebSocket origin not allowed"));
                                    } else {
                                        establishProxy(ctx, auth, protocol.orElse(null));
                                    }
                                }
                                case WebSocketUpgradeResult.Unauthorized u -> {
                                    releaseConnection();
                                    errorWriter.write(ctx, ProblemDetail.unauthorized(u.reason()));
                                }
                                case WebSocketUpgradeResult.Forbidden f -> {
                                    releaseConnection();
                                    errorWriter.write(ctx, ProblemDetail.forbidden(f.reason()));
                                }
                                case WebSocketUpgradeResult.RouteNotFound r -> {
                                    releaseConnection();
                                    errorWriter.write(ctx, ProblemDetail.routeNotFound(r.path()));
                                }
                                case WebSocketUpgradeResult.ServiceNotFound s -> {
                                    releaseConnection();
                                    errorWriter.write(ctx, ProblemDetail.serviceNotFound(s.serviceId()));
                                }
                                case WebSocketUpgradeResult.NotWebSocket n -> {
                                    releaseConnection();
                                    errorWriter.write(
                                            ctx, ProblemDetail.badRequest("Not a WebSocket endpoint: " + n.path()));
                                }
                                case WebSocketUpgradeResult.RateLimited rl -> {
                                    releaseConnection();
                                    handleRateLimited(ctx, rl);
                                }
                            }
                        },
                        error -> {
                            releaseConnection();
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
    private void establishProxy(RoutingContext ctx, WebSocketUpgradeResult.Authorized auth, String subprotocol) {
        final var sessionId = UUID.randomUUID().toString();
        final var connectionReleased = new AtomicBoolean();
        final Runnable releaseConnection = () -> {
            if (connectionReleased.compareAndSet(false, true)) {
                this.releaseConnection();
            }
        };
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
                .setURI(backendPath(backendUri))
                .setHeaders(headers)
                .setSsl("wss".equals(backendUri.getScheme()));
        if (subprotocol != null) {
            options.setSubProtocols(List.of(subprotocol));
        }

        addressResolver
                .resolve(backendUri)
                .subscribe()
                .with(
                        serverAddress -> {
                            options.setServer(serverAddress);
                            httpClient
                                    .webSocket(options)
                                    .onSuccess(backendWs -> {
                                        if (draining.get()) {
                                            backendWs.close((short) 1001, "Server shutting down");
                                            releaseConnection.run();
                                            errorWriter.write(
                                                    ctx,
                                                    ProblemDetail.serviceUnavailable("Gateway is shutting down"),
                                                    serviceId);
                                            return;
                                        }
                                        if (subprotocol != null && !subprotocol.equals(backendWs.subProtocol())) {
                                            backendWs.close((short) 1002, "Backend subprotocol mismatch");
                                            releaseConnection.run();
                                            errorWriter.write(
                                                    ctx,
                                                    ProblemDetail.badGateway("Backend subprotocol negotiation failed"),
                                                    serviceId);
                                            return;
                                        }
                                        // Backend connected - now upgrade client connection (non-blocking)
                                        if (subprotocol != null) {
                                            ctx.response().putHeader("Sec-WebSocket-Protocol", subprotocol);
                                        }
                                        ctx.request()
                                                .toWebSocket()
                                                .onSuccess(clientWs -> {
                                                    // Create message rate limit handler
                                                    final var messageHandler = createMessageRateLimitHandler(
                                                            serviceId, clientId, sessionId);

                                                    final Runnable cleanup = () -> {
                                                        if (activeSessions.remove(sessionId) != null) {
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
                                                        }
                                                        releaseConnection.run();
                                                    };

                                                    final var managedSession = new WebSocketProxySession(
                                                            sessionId,
                                                            clientWs,
                                                            backendWs,
                                                            vertx,
                                                            config,
                                                            authSessionId,
                                                            userId,
                                                            auth.token().flatMap(token -> token.identityTokenId()
                                                                    .or(() -> claim(token, "jti"))),
                                                            auth.token().flatMap(token -> token.identityIssuedAt()
                                                                    .or(() -> instantClaim(token, "iat"))),
                                                            auth.token().map(token -> token.expiresAt()),
                                                            messageHandler,
                                                            cleanup);
                                                    synchronized (activeSessions) {
                                                        if (draining.get()) {
                                                            managedSession.closeWithReason(
                                                                    (short) 1001, "Server shutting down");
                                                            return;
                                                        }
                                                        activeSessions.put(sessionId, managedSession);
                                                        metrics.incrementActiveWebSockets();
                                                        metrics.recordWebSocketConnect(serviceId);
                                                        managedSession.start();
                                                    }

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
                                                    releaseConnection.run();
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
                                        releaseConnection.run();
                                    });
                        },
                        err -> {
                            LOG.warnv(err, "Backend WebSocket address denied for service {0}", serviceId);
                            errorWriter.write(ctx, ProblemDetail.badGateway("Backend connection failed"), serviceId);
                            releaseConnection.run();
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

    private boolean reserveConnection() {
        if (draining.get()) {
            return false;
        }
        while (true) {
            if (draining.get()) {
                return false;
            }
            final var current = connectionReservations.get();
            if (current >= config.maxConnections()) {
                return false;
            }
            if (connectionReservations.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseConnection() {
        connectionReservations.updateAndGet(current -> Math.max(0, current - 1));
    }

    private boolean isOriginAllowed(RoutingContext ctx, WebSocketUpgradeResult.Authorized auth) {
        final var origin = ctx.request().getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (origin.length() > 4096) {
            return false;
        }
        final var allowedOrigins = auth.route()
                .service()
                .corsConfig()
                .map(serviceCors -> serviceCors.allowedOrigins())
                .orElseGet(globalCorsConfig::allowedOrigins);
        return allowedOrigins.stream().anyMatch(allowed -> !"*".equals(allowed) && origin.equals(allowed));
    }

    private boolean hasRequestedSubprotocol(RoutingContext ctx) {
        final var header = requestHeader(ctx, "Sec-WebSocket-Protocol");
        return header != null && !header.isBlank();
    }

    private Optional<String> selectSubprotocol(RoutingContext ctx) {
        final var allowed = config.allowedSubprotocols().orElse(List.of());
        return selectSubprotocol(requestHeader(ctx, "Sec-WebSocket-Protocol"), allowed);
    }

    private Optional<String> selectSubprotocol(String header, List<String> allowed) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        final var requested =
                Arrays.stream(header.split(",", -1)).map(String::trim).toList();
        return requested.stream().allMatch(WebSocketGateway::isProtocolToken)
                ? requested.stream().filter(allowed::contains).findFirst()
                : Optional.empty();
    }

    private String requestHeader(RoutingContext ctx, String name) {
        return String.join(",", ctx.request().headers().getAll(name));
    }

    private static boolean isProtocolToken(String value) {
        return !value.isEmpty() && value.chars().allMatch(WebSocketGateway::isProtocolTokenChar);
    }

    private static boolean isProtocolTokenChar(int c) {
        return c >= 'a' && c <= 'z'
                || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private String backendPath(URI uri) {
        var path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
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

    void onRevocation(RevocationEvent event) {
        activeSessions.values().stream()
                .filter(session -> session.shouldCloseFor(event))
                .forEach(session -> session.closeWithReason((short) 1008, "Authentication revoked"));
    }

    void onShutdown(@Observes ShutdownEvent event) {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        final List<WebSocketProxySession> sessions;
        synchronized (activeSessions) {
            sessions = List.copyOf(activeSessions.values());
        }
        sessions.forEach(session -> session.closeWithReason((short) 1001, "Server shutting down"));
    }

    private static Optional<String> claim(AussieToken token, String name) {
        return Optional.ofNullable(token.claims().get(name))
                .map(Object::toString)
                .filter(value -> !value.isBlank());
    }

    private static Optional<Instant> instantClaim(AussieToken token, String name) {
        final var value = token.claims().get(name);
        try {
            if (value instanceof Instant instant) {
                return Optional.of(instant);
            }
            if (value != null) {
                return Optional.of(Instant.ofEpochSecond(Long.parseLong(value.toString())));
            }
        } catch (DateTimeException | NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
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
