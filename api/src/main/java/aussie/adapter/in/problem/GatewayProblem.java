package aussie.adapter.in.problem;

import java.net.URI;

import io.quarkiverse.httpproblem.HttpProblem;

import aussie.core.model.gateway.GatewayResult;

/**
 * RFC 9457 Problem Details factory for gateway errors.
 *
 * <p>Provides static factory methods that create {@link HttpProblem} instances
 * from quarkus-http-problem for consistent error responses across all
 * gateway endpoints. The body shape is owned by {@link ProblemDetail}; both
 * this class and the native Vert.x error path read from the same factories
 * so the two paths emit identical responses.
 */
public final class GatewayProblem {

    private GatewayProblem() {}

    private static HttpProblem build(ProblemDetail problem) {
        final var builder = HttpProblem.builder()
                .withType(URI.create(problem.type()))
                .withTitle(problem.title())
                .withStatus(problem.status())
                .withDetail(problem.detail())
                .with("code", problem.code());
        problem.extras().forEach(builder::with);
        return builder.build();
    }

    // ========== Not Found Errors ==========

    public static HttpProblem serviceNotFound(String serviceId) {
        return build(ProblemDetail.serviceNotFound(serviceId));
    }

    public static HttpProblem routeNotFound(String path) {
        return build(ProblemDetail.routeNotFound(path));
    }

    public static HttpProblem resourceNotFound(String resourceType, String resourceId) {
        return build(ProblemDetail.resourceNotFound(resourceType, resourceId));
    }

    public static HttpProblem notFound(String detail) {
        return build(ProblemDetail.notFound(detail));
    }

    // ========== Bad Request Errors ==========

    public static HttpProblem badRequest(String detail) {
        return build(ProblemDetail.badRequest(detail));
    }

    public static HttpProblem validationError(String detail) {
        return build(ProblemDetail.validationError(detail));
    }

    // ========== Authentication/Authorization Errors ==========

    public static HttpProblem unauthorized(String detail) {
        return build(ProblemDetail.unauthorized(detail));
    }

    public static HttpProblem forbidden(String detail) {
        return build(ProblemDetail.forbidden(detail));
    }

    // ========== Gateway Errors ==========

    public static HttpProblem badGateway(String detail) {
        return build(ProblemDetail.badGateway(detail));
    }

    public static HttpProblem gatewayTimeout(String detail) {
        return build(ProblemDetail.gatewayTimeout(detail));
    }

    public static HttpProblem serviceUnavailable(String detail) {
        return build(ProblemDetail.serviceUnavailable(detail));
    }

    public static HttpProblem from(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Success ignored -> internalError("Invalid proxy plan");
            case GatewayResult.RouteNotFound route -> routeNotFound(route.path());
            case GatewayResult.ServiceNotFound service -> serviceNotFound(service.serviceId());
            case GatewayResult.ReservedPath reserved -> notFound("Path '%s' is reserved".formatted(reserved.path()));
            case GatewayResult.Error error -> badGateway(error.message());
            case GatewayResult.Unauthorized unauthorized -> unauthorized(unauthorized.reason());
            case GatewayResult.Forbidden forbidden -> forbidden(forbidden.reason());
            case GatewayResult.BadRequest badRequest -> badRequest(badRequest.reason());
        };
    }

    // ========== Rate Limit Errors ==========

    /**
     * Create a 429 Too Many Requests problem with full rate limit details.
     */
    public static HttpProblem tooManyRequests(
            String detail, long retryAfterSeconds, long limit, long remaining, long resetAt) {
        return build(ProblemDetail.tooManyRequests(detail, retryAfterSeconds, limit, remaining, resetAt));
    }

    /**
     * Create a 429 Too Many Requests problem with minimal details.
     */
    public static HttpProblem tooManyRequests(String detail, long retryAfterSeconds) {
        return build(ProblemDetail.tooManyRequests(detail, retryAfterSeconds));
    }

    // ========== Request Size Errors ==========

    public static HttpProblem payloadTooLarge(String detail) {
        return build(ProblemDetail.payloadTooLarge(detail));
    }

    public static HttpProblem headerTooLarge(String detail) {
        return build(ProblemDetail.headerTooLarge(detail));
    }

    // ========== Conflict Errors ==========

    public static HttpProblem conflict(String detail) {
        return build(ProblemDetail.conflict(detail));
    }

    public static HttpProblem preconditionFailed(String detail) {
        return build(ProblemDetail.preconditionFailed(detail));
    }

    // ========== Server Errors ==========

    public static HttpProblem internalError(String detail) {
        return build(ProblemDetail.internalError(detail));
    }

    // ========== Feature Disabled ==========

    public static HttpProblem featureDisabled(String feature) {
        return build(ProblemDetail.featureDisabled(feature));
    }
}
