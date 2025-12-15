package aussie.adapter.in.problem;

import jakarta.ws.rs.core.Response.Status;

import io.quarkiverse.resteasy.problem.HttpProblem;

/**
 * RFC 7807 Problem Details factory for gateway errors.
 *
 * <p>Provides static factory methods that create {@link HttpProblem} instances
 * from quarkus-resteasy-problem for consistent error responses across all
 * gateway endpoints.
 *
 * <p>All 4xx client errors are created with {@code logLevel=OFF} to prevent
 * unnecessary error logging for expected client behavior.
 */
public final class GatewayProblem {

    private GatewayProblem() {
        // Utility class - prevent instantiation
    }

    // ========== Not Found Errors ==========

    public static HttpProblem serviceNotFound(String serviceId) {
        return HttpProblem.builder()
                .withTitle("Service Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail("Service '%s' is not registered".formatted(serviceId))
                .build();
    }

    public static HttpProblem routeNotFound(String path) {
        return HttpProblem.builder()
                .withTitle("Route Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail("No route matches path '%s'".formatted(path))
                .build();
    }

    public static HttpProblem resourceNotFound(String resourceType, String resourceId) {
        return HttpProblem.builder()
                .withTitle("%s Not Found".formatted(resourceType))
                .withStatus(Status.NOT_FOUND)
                .withDetail("%s not found: %s".formatted(resourceType, resourceId))
                .build();
    }

    public static HttpProblem notFound(String detail) {
        return HttpProblem.builder()
                .withTitle("Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail(detail)
                .build();
    }

    // ========== Bad Request Errors ==========

    public static HttpProblem badRequest(String detail) {
        return HttpProblem.builder()
                .withTitle("Bad Request")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem validationError(String detail) {
        return HttpProblem.builder()
                .withTitle("Validation Error")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(detail)
                .build();
    }

    // ========== Authentication/Authorization Errors ==========

    public static HttpProblem unauthorized(String detail) {
        return HttpProblem.builder()
                .withTitle("Unauthorized")
                .withStatus(Status.UNAUTHORIZED)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem forbidden(String detail) {
        return HttpProblem.builder()
                .withTitle("Forbidden")
                .withStatus(Status.FORBIDDEN)
                .withDetail(detail)
                .build();
    }

    // ========== Gateway Errors ==========

    public static HttpProblem badGateway(String detail) {
        return HttpProblem.builder()
                .withTitle("Bad Gateway")
                .withStatus(Status.BAD_GATEWAY)
                .withDetail(detail)
                .build();
    }

    // ========== Rate Limit Errors ==========

    /**
     * Create a 429 Too Many Requests problem with full rate limit details.
     *
     * @param detail the error detail message
     * @param retryAfterSeconds seconds until client can retry
     * @param limit the rate limit
     * @param remaining remaining requests (typically 0)
     * @param resetAt Unix timestamp when limit resets
     * @return rate limit problem
     */
    public static HttpProblem tooManyRequests(
            String detail, long retryAfterSeconds, long limit, long remaining, long resetAt) {
        return HttpProblem.builder()
                .withTitle("Too Many Requests")
                .withStatus(Status.fromStatusCode(429))
                .withDetail(detail)
                .with("retryAfter", retryAfterSeconds)
                .with("limit", limit)
                .with("remaining", remaining)
                .with("resetAt", resetAt)
                .build();
    }

    /**
     * Create a 429 Too Many Requests problem with minimal details.
     *
     * @param detail the error detail message
     * @param retryAfterSeconds seconds until client can retry
     * @return rate limit problem
     */
    public static HttpProblem tooManyRequests(String detail, long retryAfterSeconds) {
        return HttpProblem.builder()
                .withTitle("Too Many Requests")
                .withStatus(Status.fromStatusCode(429))
                .withDetail(detail)
                .with("retryAfter", retryAfterSeconds)
                .build();
    }

    // ========== Request Size Errors ==========

    public static HttpProblem payloadTooLarge(String detail) {
        return HttpProblem.builder()
                .withTitle("Payload Too Large")
                .withStatus(Status.REQUEST_ENTITY_TOO_LARGE)
                .withDetail(detail)
                .build();
    }

    public static HttpProblem headerTooLarge(String detail) {
        return HttpProblem.builder()
                .withTitle("Request Header Fields Too Large")
                .withStatus(Status.fromStatusCode(431))
                .withDetail(detail)
                .build();
    }

    // ========== Conflict Errors ==========

    public static HttpProblem conflict(String detail) {
        return HttpProblem.builder()
                .withTitle("Conflict")
                .withStatus(Status.CONFLICT)
                .withDetail(detail)
                .build();
    }

    // ========== Server Errors ==========

    public static HttpProblem internalError(String detail) {
        return HttpProblem.builder()
                .withTitle("Internal Server Error")
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .withDetail(detail)
                .build();
    }

    // ========== Feature Disabled ==========

    public static HttpProblem featureDisabled(String feature) {
        return HttpProblem.builder()
                .withTitle("Feature Disabled")
                .withStatus(Status.NOT_FOUND)
                .withDetail("%s is disabled".formatted(feature))
                .build();
    }
}
