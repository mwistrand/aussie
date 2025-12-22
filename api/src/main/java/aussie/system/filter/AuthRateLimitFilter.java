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
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.service.auth.AuthRateLimitService;
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

    @Inject
    public AuthRateLimitFilter(
            AuthRateLimitService rateLimitService,
            AuthRateLimitConfig config,
            SecurityEventDispatcher securityEventDispatcher,
            TelemetryHelper telemetryHelper,
            FailedAttemptRepository failedAttemptRepository) {
        this.rateLimitService = rateLimitService;
        this.config = config;
        this.securityEventDispatcher = securityEventDispatcher;
        this.telemetryHelper = telemetryHelper;
        this.failedAttemptRepository = failedAttemptRepository;
    }

    /**
     * Reactive filter method for authentication rate limiting.
     *
     * @param requestContext the request context
     * @return Uni with null to continue, or Response to abort
     */
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 100)
    public Uni<Response> filter(ContainerRequestContext requestContext) {
        if (!config.enabled()) {
            return Uni.createFrom().nullItem();
        }

        final var path = requestContext.getUriInfo().getPath();

        // Only apply to auth endpoints
        if (!isAuthEndpoint(path)) {
            return Uni.createFrom().nullItem();
        }

        final var ip = extractClientIp(requestContext);
        final var identifier = extractIdentifier(requestContext);

        return rateLimitService.checkAuthLimit(ip, identifier).map(result -> {
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
        failedAttemptRepository.getLockoutCount(result.key()).subscribe().with(lockoutCount -> {
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

    private String extractClientIp(ContainerRequestContext ctx) {
        // RFC 7239 Forwarded header (preferred)
        final var forwarded = ctx.getHeaderString("Forwarded");
        if (forwarded != null) {
            final var ip = parseForwardedFor(forwarded);
            if (ip != null) {
                return ip;
            }
        }

        // X-Forwarded-For fallback
        final var xForwardedFor = ctx.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null) {
            return xForwardedFor.split(",")[0].trim();
        }

        return "unknown";
    }

    /**
     * Parse the client IP from RFC 7239 Forwarded header.
     *
     * @param forwarded the Forwarded header value
     * @return the client IP, or null if not found
     */
    private String parseForwardedFor(String forwarded) {
        // Handle multiple forwarded entries (take first one - closest to client)
        final var firstEntry = forwarded.split(",")[0].trim();

        for (final var part : firstEntry.split(";")) {
            final var trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("for=")) {
                var value = trimmed.substring(4);
                // Remove quotes if present
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                // Handle IPv6 brackets - extract address and skip port stripping
                if (value.startsWith("[")) {
                    final var bracketEnd = value.indexOf(']');
                    if (bracketEnd > 0) {
                        // Extract just the IPv6 address without brackets (port is after bracket)
                        return value.substring(1, bracketEnd);
                    }
                }
                // Remove port if present (for IPv4 only - identified by having exactly one colon)
                final var colonCount = value.length() - value.replace(":", "").length();
                if (colonCount == 1) {
                    final var colonIndex = value.indexOf(':');
                    value = value.substring(0, colonIndex);
                }
                return value;
            }
        }
        return null;
    }

    private String extractIdentifier(ContainerRequestContext ctx) {
        // Try to extract identifier from request body or headers
        // For login requests, this would be username/email
        // For API key requests, this would be the key prefix

        // Check for API key header
        final var apiKey = ctx.getHeaderString("X-API-Key");
        if (apiKey != null && apiKey.length() >= 8) {
            return apiKey.substring(0, 8);
        }

        // Check for authorization header (bearer token)
        final var auth = ctx.getHeaderString("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            // Use first 8 chars of token as identifier
            final var token = auth.substring(7);
            if (token.length() >= 8) {
                return token.substring(0, 8);
            }
        }

        // For other cases, identifier will be extracted from request body
        // by the authentication mechanism and recorded after failed attempt
        return null;
    }

    private String hashClientId(String clientId) {
        if (clientId == null) {
            return "unknown";
        }
        final var hash = Integer.toHexString(clientId.hashCode());
        return hash.substring(0, Math.min(8, hash.length()));
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
