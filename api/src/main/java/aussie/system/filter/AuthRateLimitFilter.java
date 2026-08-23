package aussie.system.filter;

import java.time.Instant;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import io.opentelemetry.api.trace.Span;
import io.quarkus.arc.properties.IfBuildProperty;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.service.auth.AuthRateLimitService;
import aussie.core.util.SecureHash;
import aussie.spi.FailedAttemptRepository;
import aussie.spi.SecurityEvent;

/**
 * Reactive filter that enforces authentication rate limits.
 *
 * <p>This filter runs before authentication to check if the client is locked
 * out due to too many failed authentication attempts. It protects auth endpoints
 * against brute force attacks.
 *
 * <p>Runs at priority AUTHENTICATION - 100, before the general rate limit filter,
 * to prevent locked-out clients from consuming rate limit tokens.
 */
@IfBuildProperty(name = "aussie.auth.rate-limit.enabled", stringValue = "true", enableIfMissing = true)
public class AuthRateLimitFilter {

    private static final String AUTH_PATH_PREFIX = "/auth";
    private static final String ADMIN_PATH_PREFIX = "/admin";
    private static final String AUTH_RATE_LIMIT_RESULT = "aussie.auth.ratelimit.result";

    private final AuthRateLimitService rateLimitService;
    private final AuthRateLimitConfig config;
    private final SecurityEventDispatcher securityEventDispatcher;
    private final TelemetryHelper telemetryHelper;
    private final FailedAttemptRepository failedAttemptRepository;
    private final ClientContextResolver clientContextResolver;

    @Inject
    public AuthRateLimitFilter(
            AuthRateLimitService rateLimitService,
            AuthRateLimitConfig config,
            SecurityEventDispatcher securityEventDispatcher,
            TelemetryHelper telemetryHelper,
            FailedAttemptRepository failedAttemptRepository,
            ClientContextResolver clientContextResolver) {
        this.rateLimitService = rateLimitService;
        this.config = config;
        this.securityEventDispatcher = securityEventDispatcher;
        this.telemetryHelper = telemetryHelper;
        this.failedAttemptRepository = failedAttemptRepository;
        this.clientContextResolver = clientContextResolver;
    }

    /**
     * Reactive filter method for authentication rate limiting.
     *
     * @param requestContext the request context
     * @param vertxRequest the Vert.x request, used to obtain the socket-level remote address
     * @return Uni with null to continue, or Response to abort
     */
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 100)
    public Uni<Response> filter(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        if (!config.enabled()) {
            return Uni.createFrom().nullItem();
        }

        final var path = requestContext.getUriInfo().getPath();

        // Only apply to auth endpoints
        if (!isAuthEndpoint(path)) {
            return Uni.createFrom().nullItem();
        }

        final var ip = extractClientIp(requestContext, vertxRequest);

        return rateLimitService.checkAuthLimit(ip, null).map(result -> {
            requestContext.setProperty(AUTH_RATE_LIMIT_RESULT, result);
            setSpanAttributes(result);

            if (!result.allowed()) {
                dispatchLockoutEvent(result, ip);
                setLockoutSpanAttributes(result);
                return buildLockoutResponse(result);
            }

            return null;
        });
    }

    private boolean isAuthEndpoint(String path) {
        // Apply to /auth/* endpoints and authentication-related admin endpoints
        return path.startsWith(AUTH_PATH_PREFIX)
                || path.startsWith(ADMIN_PATH_PREFIX + "/sessions")
                || path.startsWith(ADMIN_PATH_PREFIX + "/api-keys");
    }

    private void dispatchLockoutEvent(AuthRateLimitService.RateLimitResult result, String ip) {
        // Get lockout count for the event
        failedAttemptRepository
                .getLockoutCount(result.key())
                .onFailure()
                .recoverWithItem(0)
                .subscribe()
                .with(lockoutCount -> {
                    final var event = new SecurityEvent.AuthenticationLockout(
                            Instant.now(),
                            hashClientId(ip),
                            result.key(),
                            0, // Will be filled by getLockoutInfo if needed
                            result.retryAfterSeconds(),
                            lockoutCount);
                    securityEventDispatcher.dispatch(event);
                });
    }

    private Response buildLockoutResponse(AuthRateLimitService.RateLimitResult result) {
        final var detail = "Too many failed authentication attempts. Retry after %d seconds."
                .formatted(result.retryAfterSeconds());

        final var resetAt = result.lockoutExpiry() != null
                ? result.lockoutExpiry().getEpochSecond()
                : Instant.now().plusSeconds(result.retryAfterSeconds()).getEpochSecond();

        var builder = Response.status(429)
                .header("Retry-After", result.retryAfterSeconds())
                .entity(GatewayProblem.tooManyRequests(
                        detail,
                        result.retryAfterSeconds(),
                        0, // limit not applicable for lockout
                        0, // remaining not applicable for lockout
                        resetAt));

        if (config.includeHeaders()) {
            builder.header("X-Auth-Lockout-Key", result.key()).header("X-Auth-Lockout-Reset", resetAt);
        }

        return builder.build();
    }

    private void setSpanAttributes(AuthRateLimitService.RateLimitResult result) {
        final var span = Span.current();
        telemetryHelper.setAuthRateLimited(span, !result.allowed());
    }

    private void setLockoutSpanAttributes(AuthRateLimitService.RateLimitResult result) {
        final var span = Span.current();
        telemetryHelper.setAuthLockoutKey(span, result.key());
        telemetryHelper.setAuthLockoutRetryAfter(span, result.retryAfterSeconds());
    }

    private String extractClientIp(ContainerRequestContext ctx, HttpServerRequest vertxRequest) {
        return clientContextResolver.getOrCompute(ctx, vertxRequest).resolvedIp();
    }

    private String hashClientId(String clientId) {
        if (clientId == null) {
            return "unknown";
        }
        return SecureHash.truncatedSha256(clientId, 16);
    }

    /**
     * Get the rate limit result from the request context.
     *
     * <p>This can be used by authentication mechanisms to check if the
     * request was already rate limited.
     *
     * @param ctx the request context
     * @return the rate limit result, or empty if not checked
     */
    public static Optional<AuthRateLimitService.RateLimitResult> getRateLimitResult(ContainerRequestContext ctx) {
        return Optional.ofNullable((AuthRateLimitService.RateLimitResult) ctx.getProperty(AUTH_RATE_LIMIT_RESULT));
    }
}
