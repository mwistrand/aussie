package aussie.system.filter;

import java.time.Instant;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.config.RateLimitingConfig;
import aussie.core.model.EffectiveRateLimit;
import aussie.core.model.RateLimitDecision;
import aussie.core.model.RateLimitKey;
import aussie.core.model.RouteMatch;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.RateLimitResolver;
import aussie.spi.SecurityEvent;

/**
 * JAX-RS filter that enforces rate limits on incoming requests.
 *
 * <p>
 * This filter runs early in the request chain (before authentication) to
 * reject excessive traffic before incurring authentication overhead.
 *
 * <p>
 * Rate limits are resolved using the {@link RouteMatch} from the request
 * context
 * (set by {@link RequestContextFilter}). When a route match exists, service and
 * endpoint-specific limits apply. Otherwise, platform defaults are used.
 *
 * <p>
 * Client identification priority:
 * <ol>
 * <li>Session ID from cookie or header</li>
 * <li>Authorization header (bearer token hash)</li>
 * <li>API key ID header</li>
 * <li>Client IP from X-Forwarded-For or remote address</li>
 * </ol>
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 50)
public class RateLimitFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String RATE_LIMIT_DECISION_ATTR = "aussie.ratelimit.decision";
    private static final String SESSION_COOKIE = "aussie_session";

    private final RateLimiter rateLimiter;
    private final Instance<RateLimitingConfig> configInstance;
    private final Metrics metrics;
    private final SecurityEventDispatcher securityEventDispatcher;
    private final RateLimitResolver rateLimitResolver;

    @Inject
    public RateLimitFilter(
            RateLimiter rateLimiter,
            Instance<RateLimitingConfig> configInstance,
            Metrics metrics,
            SecurityEventDispatcher securityEventDispatcher,
            RateLimitResolver rateLimitResolver) {
        this.rateLimiter = rateLimiter;
        this.configInstance = configInstance;
        this.metrics = metrics;
        this.securityEventDispatcher = securityEventDispatcher;
        this.rateLimitResolver = rateLimitResolver;
    }

    private RateLimitingConfig config() {
        return configInstance.get();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!config().enabled()) {
            return;
        }

        final var routeMatch = (RouteMatch) requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY);
        final var serviceId = extractServiceId(requestContext);
        final var clientId = extractClientId(requestContext);

        // Resolve limit synchronously - either from route match or platform defaults
        final var effectiveLimit = resolveEffectiveLimit(routeMatch);
        final var endpointId = routeMatch != null ? routeMatch.endpoint().path() : null;
        final var key = RateLimitKey.http(clientId, serviceId, endpointId);

        rateLimiter
                .checkAndConsume(key, effectiveLimit)
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .subscribe()
                .with((decision) -> {
                    handleDecision(requestContext, decision, serviceId, clientId);
                });
    }

    private void handleDecision(
            ContainerRequestContext ctx, RateLimitDecision decision, String serviceId, String clientId) {

        ctx.setProperty(RATE_LIMIT_DECISION_ATTR, decision);
        recordMetrics(serviceId, decision);
        setSpanAttributes(decision);

        if (!decision.allowed()) {
            handleRateLimitExceeded(ctx, decision, serviceId, clientId);
        }
    }

    private void handleRateLimitExceeded(
            ContainerRequestContext ctx, RateLimitDecision decision, String serviceId, String clientId) {

        metrics.recordRateLimitExceeded(serviceId, "http");
        dispatchSecurityEvent(decision, serviceId, clientId);
        setExceededSpanAttributes(decision);
        ctx.abortWith(buildRateLimitResponse(decision));
    }

    private void recordMetrics(String serviceId, RateLimitDecision decision) {
        metrics.recordRateLimitCheck(serviceId, decision.allowed(), decision.remaining());
    }

    private void setSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        span.setAttribute(SpanAttributes.RATE_LIMITED, !decision.allowed());
        span.setAttribute(SpanAttributes.RATE_LIMIT_REMAINING, decision.remaining());
        span.setAttribute(SpanAttributes.RATE_LIMIT_TYPE, SpanAttributes.RATE_LIMIT_TYPE_HTTP);
    }

    private void setExceededSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        span.setAttribute(SpanAttributes.RATE_LIMIT_RETRY_AFTER, decision.retryAfterSeconds());
        // Use OK status - rate limiting is expected behavior, not an error
        // Errors would trigger alerts; rate limits are informational
        span.setStatus(StatusCode.OK, "Rate limit exceeded");
    }

    private void dispatchSecurityEvent(RateLimitDecision decision, String serviceId, String clientId) {

        final var event = new SecurityEvent.RateLimitExceeded(
                Instant.now(), hashClientId(clientId), serviceId, decision.requestCount(), (int) decision.limit(), (int)
                        decision.windowSeconds());
        securityEventDispatcher.dispatch(event);
    }

    private Response buildRateLimitResponse(RateLimitDecision decision) {
        final var detail = "Rate limit exceeded. Retry after %d seconds.".formatted(decision.retryAfterSeconds());

        return Response.status(429)
                .header("Retry-After", decision.retryAfterSeconds())
                .header("X-RateLimit-Limit", decision.limit())
                .header("X-RateLimit-Remaining", 0)
                .header("X-RateLimit-Reset", decision.resetAtEpochSeconds())
                .entity(GatewayProblem.tooManyRequests(
                        detail, decision.retryAfterSeconds(), decision.limit(), 0, decision.resetAtEpochSeconds()))
                .build();
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {

        if (!config().includeHeaders()) {
            return;
        }
        addRateLimitHeaders(requestContext, responseContext);
    }

    private void addRateLimitHeaders(ContainerRequestContext req, ContainerResponseContext res) {

        final var decision = (RateLimitDecision) req.getProperty(RATE_LIMIT_DECISION_ATTR);
        if (decision != null && decision.allowed()) {
            res.getHeaders().add("X-RateLimit-Limit", decision.limit());
            res.getHeaders().add("X-RateLimit-Remaining", decision.remaining());
            res.getHeaders().add("X-RateLimit-Reset", decision.resetAtEpochSeconds());
        }
    }

    // -------------------------------------------------------------------------
    // Client Identification (priority: session > auth > api key > IP)
    // -------------------------------------------------------------------------

    private String extractClientId(ContainerRequestContext ctx) {
        return extractSessionId(ctx)
                .or(() -> extractAuthHeaderHash(ctx))
                .or(() -> extractApiKeyId(ctx))
                .orElseGet(() -> extractClientIp(ctx));
    }

    private Optional<String> extractSessionId(ContainerRequestContext ctx) {
        final var cookie = ctx.getCookies().get(SESSION_COOKIE);
        if (cookie != null) {
            return Optional.of("session:" + cookie.getValue());
        }
        final var header = ctx.getHeaderString("X-Session-ID");
        return Optional.ofNullable(header).map(h -> "session:" + h);
    }

    private Optional<String> extractAuthHeaderHash(ContainerRequestContext ctx) {
        final var auth = ctx.getHeaderString("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return Optional.of("bearer:" + hashToken(auth.substring(7)));
        }
        return Optional.empty();
    }

    private Optional<String> extractApiKeyId(ContainerRequestContext ctx) {
        return Optional.ofNullable(ctx.getHeaderString("X-API-Key-ID")).map(id -> "apikey:" + id);
    }

    private String extractClientIp(ContainerRequestContext ctx) {
        final var forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:unknown";
    }

    // -------------------------------------------------------------------------
    // Service Extraction
    // -------------------------------------------------------------------------

    private String extractServiceId(ContainerRequestContext ctx) {
        final var serviceId = (String) ctx.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY);
        return serviceId != null ? serviceId : "unknown";
    }

    // -------------------------------------------------------------------------
    // Rate Limit Resolution
    // -------------------------------------------------------------------------

    private EffectiveRateLimit resolveEffectiveLimit(RouteMatch routeMatch) {
        if (routeMatch != null) {
            return rateLimitResolver.resolveHttpLimit(routeMatch);
        }
        return rateLimitResolver.resolvePlatformDefaults();
    }

    // -------------------------------------------------------------------------
    // Utility Methods
    // -------------------------------------------------------------------------

    private String hashClientId(String clientId) {
        if (clientId == null) {
            return "unknown";
        }
        final var hash = Integer.toHexString(clientId.hashCode());
        return hash.substring(0, Math.min(8, hash.length()));
    }

    private String hashToken(String token) {
        return Integer.toHexString(token.hashCode());
    }
}
