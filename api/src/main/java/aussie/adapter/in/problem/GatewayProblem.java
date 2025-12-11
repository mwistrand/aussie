package aussie.adapter.in.problem;

import jakarta.ws.rs.core.Response.Status;

import io.quarkiverse.resteasy.problem.HttpProblem;

/**
 * RFC 7807 Problem Details for gateway errors.
 *
 * <p>Extends {@link HttpProblem} from quarkus-resteasy-problem to provide
 * consistent error responses across all gateway endpoints.
 *
 * <p>Use the static factory methods to create appropriate problem instances
 * for different error scenarios.
 */
public class GatewayProblem extends HttpProblem {

    GatewayProblem(HttpProblem.Builder builder) {
        super(builder);
    }

    // ========== Not Found Errors ==========

    public static GatewayProblem serviceNotFound(String serviceId) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Service Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail("Service '%s' is not registered".formatted(serviceId)));
    }

    public static GatewayProblem routeNotFound(String path) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Route Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail("No route matches path '%s'".formatted(path)));
    }

    public static GatewayProblem resourceNotFound(String resourceType, String resourceId) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("%s Not Found".formatted(resourceType))
                .withStatus(Status.NOT_FOUND)
                .withDetail("%s not found: %s".formatted(resourceType, resourceId)));
    }

    public static GatewayProblem notFound(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail(detail));
    }

    // ========== Bad Request Errors ==========

    public static GatewayProblem badRequest(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Bad Request")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(detail));
    }

    public static GatewayProblem validationError(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Validation Error")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(detail));
    }

    // ========== Authentication/Authorization Errors ==========

    public static GatewayProblem unauthorized(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Unauthorized")
                .withStatus(Status.UNAUTHORIZED)
                .withDetail(detail));
    }

    public static GatewayProblem forbidden(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Forbidden")
                .withStatus(Status.FORBIDDEN)
                .withDetail(detail));
    }

    // ========== Gateway Errors ==========

    public static GatewayProblem badGateway(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Bad Gateway")
                .withStatus(Status.BAD_GATEWAY)
                .withDetail(detail));
    }

    // ========== Request Size Errors ==========

    public static GatewayProblem payloadTooLarge(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Payload Too Large")
                .withStatus(Status.REQUEST_ENTITY_TOO_LARGE)
                .withDetail(detail));
    }

    public static GatewayProblem headerTooLarge(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Request Header Fields Too Large")
                .withStatus(Status.fromStatusCode(431))
                .withDetail(detail));
    }

    // ========== Conflict Errors ==========

    public static GatewayProblem conflict(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Conflict")
                .withStatus(Status.CONFLICT)
                .withDetail(detail));
    }

    // ========== Server Errors ==========

    public static GatewayProblem internalError(String detail) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Internal Server Error")
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .withDetail(detail));
    }

    // ========== Feature Disabled ==========

    public static GatewayProblem featureDisabled(String feature) {
        return new GatewayProblem(HttpProblem.builder()
                .withTitle("Feature Disabled")
                .withStatus(Status.NOT_FOUND)
                .withDetail("%s is disabled".formatted(feature)));
    }
}
