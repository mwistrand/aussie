package aussie.adapter.in.websocket;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.quarkus.vertx.web.RouteFilter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.common.context.RouteContextAttributes;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.RateLimitResolver;
import aussie.core.service.routing.ServiceRegistry;
import aussie.core.util.SecureHash;
import aussie.spi.SecurityEvent;

/**
 * Vert.x route filter that enforces rate limits on WebSocket upgrade requests.
 *
 * <p>This filter runs before the WebSocket upgrade filter to reject excessive
 * connection attempts before incurring the cost of establishing connections.
 *
 * <p>Priority 40 ensures this runs after CORS (100) but before WebSocket upgrade (50).
 */
@ApplicationScoped
public class WebSocketRateLimitFilter {

    private static final Logger LOG = Logger.getLogger(WebSocketRateLimitFilter.class);
    private static final Set<String> RESERVED_PATHS = Set.of("admin", "q");

    /**
     * WebSocket close code for rate limiting (4429 mirrors HTTP 429).
     */
    public static final short WS_CLOSE_CODE_RATE_LIMITED = 4429;

    private final RateLimiter rateLimiter;
    private final RateLimitingConfig config;
    private final RateLimitResolver rateLimitResolver;
    private final ServiceRegistry serviceRegistry;
    private final GatewayMetrics metrics;
    private final SecurityEventDispatcher securityEventDispatcher;
    private final ProxyErrorWriter errorWriter;
    private final ClientContextResolver clientContextResolver;

    @Inject
    public WebSocketRateLimitFilter(
            RateLimiter rateLimiter,
            RateLimitingConfig config,
            RateLimitResolver rateLimitResolver,
            ServiceRegistry serviceRegistry,
            GatewayMetrics metrics,
            SecurityEventDispatcher securityEventDispatcher,
            ProxyErrorWriter errorWriter,
            ClientContextResolver clientContextResolver) {
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.rateLimitResolver = rateLimitResolver;
        this.serviceRegistry = serviceRegistry;
        this.metrics = metrics;
        this.securityEventDispatcher = securityEventDispatcher;
        this.errorWriter = errorWriter;
        this.clientContextResolver = clientContextResolver;
    }

    /**
     * Check rate limits for WebSocket upgrade requests.
     */
    @RouteFilter(40)
    void checkWebSocketRateLimit(RoutingContext ctx) {
        if (!isWebSocketUpgrade(ctx.request())) {
            ctx.next();
            return;
        }

        if (!isRateLimitingEnabled()) {
            ctx.next();
            return;
        }

        final var path = ctx.request().path();
        if (isReservedPath(path)) {
            ctx.next();
            return;
        }

        final var route = routeResult(ctx);
        final var serviceId =
                route == null ? extractServiceId(path) : route.service().serviceId();
        final var clientId = extractClientId(ctx);

        LOG.debugv("Checking WebSocket connection rate limit for service={0}, client={1}", serviceId, clientId);

        // Look up service and check rate limit
        final Uni<Optional<ServiceRegistration>> service = Optional.ofNullable(route)
                .map(match -> Uni.createFrom().item(Optional.of(match.service())))
                .orElseGet(() -> serviceRegistry.getServiceForRateLimiting(serviceId));
        service.flatMap(serviceOpt -> {
                    final var limit = rateLimitResolver.resolveWebSocketConnectionLimit(serviceOpt);
                    final var key = RateLimitKey.wsConnection(clientId, serviceId);
                    return rateLimiter
                            .checkAndConsume(key, limit)
                            .map(decision -> new RateLimitResult(decision, serviceOpt));
                })
                .subscribe()
                .with(result -> handleDecision(ctx, result.decision(), serviceId, clientId), error -> {
                    LOG.warnv(error, "WebSocket rate limit check failed closed");
                    errorWriter.write(
                            ctx, ProblemDetail.serviceUnavailable("Rate limit service unavailable"), serviceId);
                });
    }

    private void handleDecision(RoutingContext ctx, RateLimitDecision decision, String serviceId, String clientId) {
        recordMetrics(serviceId, decision);
        setSpanAttributes(decision);

        if (decision.allowed()) {
            ctx.next();
        } else {
            handleRateLimitExceeded(ctx, decision, serviceId, clientId);
        }
    }

    private void handleRateLimitExceeded(
            RoutingContext ctx, RateLimitDecision decision, String serviceId, String clientId) {

        metrics.recordRateLimitExceeded(serviceId, "ws_connection");
        dispatchSecurityEvent(decision, serviceId, clientId);
        setExceededSpanAttributes(decision);

        LOG.infov(
                "WebSocket connection rate limited: service={0}, client={1}, retryAfter={2}s",
                serviceId, hashClientId(clientId), decision.retryAfterSeconds());

        final var problem = ProblemDetail.tooManyRequests(
                "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.",
                decision.retryAfterSeconds(),
                decision.limit(),
                0,
                decision.resetAtEpochSeconds());
        errorWriter.writeRateLimit(
                ctx,
                problem,
                serviceId,
                decision.retryAfterSeconds(),
                decision.limit(),
                decision.resetAtEpochSeconds());
    }

    private void recordMetrics(String serviceId, RateLimitDecision decision) {
        metrics.recordRateLimitCheck(serviceId, decision.allowed(), decision.remaining());
    }

    private void setSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        span.setAttribute(SpanAttributes.RATE_LIMITED, !decision.allowed());
        span.setAttribute(SpanAttributes.RATE_LIMIT_REMAINING, decision.remaining());
        span.setAttribute(SpanAttributes.RATE_LIMIT_TYPE, SpanAttributes.RATE_LIMIT_TYPE_WS_CONNECTION);
    }

    private void setExceededSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        span.setAttribute(SpanAttributes.RATE_LIMIT_RETRY_AFTER, decision.retryAfterSeconds());
        span.setStatus(StatusCode.OK, "WebSocket connection rate limit exceeded");
    }

    private void dispatchSecurityEvent(RateLimitDecision decision, String serviceId, String clientId) {
        final var event = new SecurityEvent.RateLimitExceeded(
                Instant.now(), hashClientId(clientId), serviceId, decision.requestCount(), (int) decision.limit(), (int)
                        decision.windowSeconds());
        securityEventDispatcher.dispatch(event);
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private boolean isWebSocketUpgrade(HttpServerRequest request) {
        final var upgrade = request.getHeader("Upgrade");
        final var connection = request.getHeader("Connection");

        return "websocket".equalsIgnoreCase(upgrade)
                && connection != null
                && connection.toLowerCase(Locale.ROOT).contains("upgrade");
    }

    private boolean isRateLimitingEnabled() {
        return config.enabled()
                && rateLimiter.isEnabled()
                && config.websocket().connection().enabled();
    }

    private boolean isReservedPath(String path) {
        final var normalized = path.startsWith("/") ? path.substring(1) : path;
        final var slashIndex = normalized.indexOf('/');
        final var firstSegment = slashIndex > 0 ? normalized.substring(0, slashIndex) : normalized;
        return RESERVED_PATHS.contains(firstSegment.toLowerCase(Locale.ROOT));
    }

    private String extractServiceId(String path) {
        if (path.toLowerCase(Locale.ROOT).startsWith("/gateway/")) {
            return "gateway";
        }
        // Extract service ID from pass-through path: /{serviceId}/...
        final var normalized = path.startsWith("/") ? path.substring(1) : path;
        final var slashIndex = normalized.indexOf('/');
        return slashIndex > 0 ? normalized.substring(0, slashIndex) : normalized;
    }

    private RouteLookupResult routeResult(RoutingContext ctx) {
        final var lookup = ctx.get(RouteContextAttributes.LOOKUP);
        if (!(lookup instanceof Optional<?> routeLookup)) {
            throw new IllegalStateException("Route lookup is missing from the request context");
        }
        if (routeLookup.isEmpty()) {
            return null;
        }
        if (routeLookup.get() instanceof RouteLookupResult route) {
            return route;
        }
        throw new IllegalStateException("Route lookup contains an invalid value");
    }

    private String extractClientId(RoutingContext ctx) {
        return clientContextResolver.getOrCompute(ctx).rateLimitClientId();
    }

    private String hashClientId(String clientId) {
        if (clientId == null) {
            return "unknown";
        }
        return SecureHash.truncatedSha256(clientId, 16);
    }

    private record RateLimitResult(RateLimitDecision decision, Optional<ServiceRegistration> service) {}
}
