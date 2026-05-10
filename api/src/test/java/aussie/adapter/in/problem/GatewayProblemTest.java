package aussie.adapter.in.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GatewayProblem")
class GatewayProblemTest {

    @Nested
    @DisplayName("Not Found errors")
    class NotFoundTests {

        @Test
        @DisplayName("serviceNotFound should return 404 with service details")
        void serviceNotFound() {
            var problem = GatewayProblem.serviceNotFound("my-service");
            assertEquals(Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
            assertEquals("Service Not Found", problem.getTitle());
            assertNotNull(problem.getDetail());
        }

        @Test
        @DisplayName("routeNotFound should return 404 with path details")
        void routeNotFound() {
            var problem = GatewayProblem.routeNotFound("/api/v1/foo");
            assertEquals(Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
            assertEquals("Route Not Found", problem.getTitle());
            assertNotNull(problem.getDetail());
        }

        @Test
        @DisplayName("resourceNotFound should return 404 with resource type and ID")
        void resourceNotFound() {
            var problem = GatewayProblem.resourceNotFound("Role", "admin");
            assertEquals(Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
            assertEquals("Role Not Found", problem.getTitle());
            assertNotNull(problem.getDetail());
        }

        @Test
        @DisplayName("notFound should return generic 404")
        void notFound() {
            var problem = GatewayProblem.notFound("Item not found");
            assertEquals(Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
            assertEquals("Not Found", problem.getTitle());
            assertEquals("Item not found", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Bad Request errors")
    class BadRequestTests {

        @Test
        @DisplayName("badRequest should return 400")
        void badRequest() {
            var problem = GatewayProblem.badRequest("Invalid input");
            assertEquals(Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
            assertEquals("Bad Request", problem.getTitle());
            assertEquals("Invalid input", problem.getDetail());
        }

        @Test
        @DisplayName("validationError should return 400")
        void validationError() {
            var problem = GatewayProblem.validationError("Field 'name' is required");
            assertEquals(Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
            assertEquals("Validation Error", problem.getTitle());
            assertEquals("Field 'name' is required", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Authentication/Authorization errors")
    class AuthTests {

        @Test
        @DisplayName("unauthorized should return 401")
        void unauthorized() {
            var problem = GatewayProblem.unauthorized("Token expired");
            assertEquals(Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
            assertEquals("Unauthorized", problem.getTitle());
            assertEquals("Token expired", problem.getDetail());
        }

        @Test
        @DisplayName("forbidden should return 403")
        void forbidden() {
            var problem = GatewayProblem.forbidden("Insufficient permissions");
            assertEquals(Status.FORBIDDEN.getStatusCode(), problem.getStatusCode());
            assertEquals("Forbidden", problem.getTitle());
            assertEquals("Insufficient permissions", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Gateway errors")
    class GatewayTests {

        @Test
        @DisplayName("badGateway should return 502")
        void badGateway() {
            var problem = GatewayProblem.badGateway("Upstream unavailable");
            assertEquals(Status.BAD_GATEWAY.getStatusCode(), problem.getStatusCode());
            assertEquals("Bad Gateway", problem.getTitle());
            assertEquals("Upstream unavailable", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Rate limit errors")
    class RateLimitTests {

        @Test
        @DisplayName("tooManyRequests with full details should return 429")
        void tooManyRequestsWithDetails() {
            var problem = GatewayProblem.tooManyRequests("Rate limited", 30, 100, 0, 1709683200);
            assertEquals(429, problem.getStatusCode());
            assertEquals("Too Many Requests", problem.getTitle());
            assertEquals("Rate limited", problem.getDetail());
        }

        @Test
        @DisplayName("tooManyRequests with minimal details should return 429")
        void tooManyRequestsMinimal() {
            var problem = GatewayProblem.tooManyRequests("Slow down", 60);
            assertEquals(429, problem.getStatusCode());
            assertEquals("Too Many Requests", problem.getTitle());
            assertEquals("Slow down", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Request size errors")
    class RequestSizeTests {

        @Test
        @DisplayName("payloadTooLarge should return 413")
        void payloadTooLarge() {
            var problem = GatewayProblem.payloadTooLarge("Body exceeds 10MB");
            assertEquals(Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode(), problem.getStatusCode());
            assertEquals("Payload Too Large", problem.getTitle());
            assertEquals("Body exceeds 10MB", problem.getDetail());
        }

        @Test
        @DisplayName("headerTooLarge should return 431")
        void headerTooLarge() {
            var problem = GatewayProblem.headerTooLarge("Headers exceed 8KB");
            assertEquals(431, problem.getStatusCode());
            assertEquals("Request Header Fields Too Large", problem.getTitle());
            assertEquals("Headers exceed 8KB", problem.getDetail());
        }
    }

    @Nested
    @DisplayName("Other errors")
    class OtherTests {

        @Test
        @DisplayName("conflict should return 409")
        void conflict() {
            var problem = GatewayProblem.conflict("Resource already exists");
            assertEquals(Status.CONFLICT.getStatusCode(), problem.getStatusCode());
            assertEquals("Conflict", problem.getTitle());
            assertEquals("Resource already exists", problem.getDetail());
        }

        @Test
        @DisplayName("internalError should return 500")
        void internalError() {
            var problem = GatewayProblem.internalError("Unexpected error");
            assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), problem.getStatusCode());
            assertEquals("Internal Server Error", problem.getTitle());
            assertEquals("Unexpected error", problem.getDetail());
        }

        @Test
        @DisplayName("featureDisabled should return 404")
        void featureDisabled() {
            var problem = GatewayProblem.featureDisabled("Sessions");
            assertEquals(Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
            assertEquals("Feature Disabled", problem.getTitle());
            assertNotNull(problem.getDetail());
        }
    }
}
