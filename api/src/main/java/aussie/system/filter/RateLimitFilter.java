package aussie.system.filter;

import java.time.Instant;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Response;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.service.ServicePath;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.RateLimitResolver;
import aussie.core.service.routing.ServiceRegistry;
import aussie.core.util.SecureHash;
import aussie.spi.SecurityEvent;
import aussie.system.context.ClientContextResolver;

/**
 * Reactive filter that enforces rate limits on incoming requests.
 *
 * <p>
 * This filter runs early in the request chain (before authentication) to
 * reject excessive traffic before incurring authentication overhead.
 *
 * <p>
 * Rate limits are resolved by looking up the {@link ServiceRegistration} via
 * {@link ServiceRegistry#getService(String)} and finding the matching route.
 * When a route match exists, service and endpoint-specific limits apply.
 * Otherwise, platform defaults are used.
 *
 * <p>
 * Client identification priority:
 * <ol>
 * <li>Session ID from cookie or header</li>
 * <li>Authorization header (bearer token hash)</li>
 * <li>API key ID header</li>
 * <li>Client IP from Forwarded or X-Forwarded-For or remote address</li>
 * </ol>
 */
@Singleton
public class RateLimitFilter {

    private static final String RATE_LIMIT_DECISION_ATTR = "aussie.ratelimit.decision";
    private static final String SESSION_COOKIE = "aussie_session";

    private final RateLimiter rateLimiter;
    private final Instance<RateLimitingConfig> configInstance;
    private final Metrics metrics;
    private final SecurityEventDispatcher securityEventDispatcher;
    private final RateLimitResolver rateLimitResolver;
    private final ServiceRegistry serviceRegistry;
    private final TelemetryHelper telemetryHelper;
    private final ClientContextResolver clientContextResolver;

    @Inject
    public RateLimitFilter(
            RateLimiter rateLimiter,
            Instance<RateLimitingConfig> configInstance,
            Metrics metrics,
            SecurityEventDispatcher securityEventDispatcher,
            RateLimitResolver rateLimitResolver,
            ServiceRegistry serviceRegistry,
            TelemetryHelper telemetryHelper,
            ClientContextResolver clientContextResolver) {
        this.rateLimiter = rateLimiter;
        this.configInstance = configInstance;
        this.metrics = metrics;
        this.securityEventDispatcher = securityEventDispatcher;
        this.rateLimitResolver = rateLimitResolver;
        this.serviceRegistry = serviceRegistry;
        this.telemetryHelper = telemetryHelper;
        this.clientContextResolver = clientContextResolver;
    }

    private RateLimitingConfig config() {
        return configInstance.get();
    }

    /**
     * Request filter that checks rate limits.
     *
     * <p>Returns null to continue processing, or a Response to abort with rate limit error.
     */
    @ServerRequestFilter(priority = jakarta.ws.rs.Priorities.AUTHENTICATION - 50)
    public Uni<Response> filterRequest(ContainerRequestContext requestContext, HttpServerRequest request) {
        if (!config().enabled()) {
            return Uni.createFrom().nullItem();
        }

        final var path = request.path();
        final var method = request.method().name();
        final var servicePath = ServicePath.parse(path);
        final var serviceId = servicePath.serviceId();
        final var clientId = extractClientId(requestContext, request);

        final RouteLookupResult routeResult =
                serviceRegistry.findRoute(path, method).orElse(null);

        final EffectiveRateLimit effectiveLimit =
                routeResult != null ? resolveEffectiveLimit(routeResult) : rateLimitResolver.resolvePlatformDefaults();

        final var endpointId =
                routeResult != null ? routeResult.endpoint().map(e -> e.path()).orElse(null) : null;
        final var key = RateLimitKey.http(clientId, serviceId, endpointId);

        return rateLimiter.checkAndConsume(key, effectiveLimit).map(decision -> {
            requestContext.setProperty(RATE_LIMIT_DECISION_ATTR, decision);

            recordMetrics(serviceId, decision);
            setSpanAttributes(decision);

            if (!decision.allowed()) {
                metrics.recordRateLimitExceeded(serviceId, "http");
                dispatchSecurityEvent(decision, serviceId, clientId);
                setExceededSpanAttributes(decision);
                return buildRateLimitResponse(decision);
            }

            // Return null to continue processing
            return null;
        });
    }

    /**
     * Response filter that adds rate limit headers.
     */
    @ServerResponseFilter
    public void filterResponse(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!config().includeHeaders()) {
            return;
        }

        // Read decision from request context property
        final var decision = (RateLimitDecision) requestContext.getProperty(RATE_LIMIT_DECISION_ATTR);
        if (decision != null && decision.allowed()) {
            responseContext.getHeaders().add("X-RateLimit-Limit", decision.limit());
            responseContext.getHeaders().add("X-RateLimit-Remaining", decision.remaining());
            responseContext.getHeaders().add("X-RateLimit-Reset", decision.resetAtEpochSeconds());
        }
    }

    private void recordMetrics(String serviceId, RateLimitDecision decision) {
        metrics.recordRateLimitCheck(serviceId, decision.allowed(), decision.remaining());
    }

    private void setSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        telemetryHelper.setRateLimited(span, !decision.allowed());
        telemetryHelper.setRateLimitRemaining(span, decision.remaining());
        telemetryHelper.setRateLimitType(span, SpanAttributes.RATE_LIMIT_TYPE_HTTP);
    }

    private void setExceededSpanAttributes(RateLimitDecision decision) {
        final var span = Span.current();
        telemetryHelper.setRateLimitRetryAfter(span, decision.retryAfterSeconds());
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

    // -------------------------------------------------------------------------
    // Client Identification (priority: session > auth > api key > IP)
    // -------------------------------------------------------------------------

    private String extractClientId(ContainerRequestContext requestContext, HttpServerRequest request) {
        return extractSessionId(request)
                .or(() -> extractAuthHeaderHash(request))
                .or(() -> extractApiKeyId(request))
                .orElseGet(() -> "ip:"
                        + clientContextResolver
                                .getOrCompute(requestContext, request)
                                .resolvedIp());
    }

    private Optional<String> extractSessionId(HttpServerRequest request) {
        final var cookie = request.getCookie(SESSION_COOKIE);
        if (cookie != null) {
            return Optional.of("session:" + cookie.getValue());
        }
        final var header = request.getHeader("X-Session-ID");
        return Optional.ofNullable(header).map(h -> "session:" + h);
    }

    private Optional<String> extractAuthHeaderHash(HttpServerRequest request) {
        final var auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return Optional.of("bearer:" + hashToken(auth.substring(7)));
        }
        return Optional.empty();
    }

    private Optional<String> extractApiKeyId(HttpServerRequest request) {
        return Optional.ofNullable(request.getHeader("X-API-Key-ID")).map(id -> "apikey:" + id);
    }

    private EffectiveRateLimit resolveEffectiveLimit(RouteLookupResult routeResult) {
        if (routeResult != null) {
            return rateLimitResolver.resolveLimit(routeResult);
        }
        return rateLimitResolver.resolvePlatformDefaults();
    }

    private String hashClientId(String clientId) {
        if (clientId == null) {
            return "unknown";
        }
        return SecureHash.truncatedSha256(clientId, 16);
    }

    private String hashToken(String token) {
        return SecureHash.truncatedSha256(token, 16);
    }
}
