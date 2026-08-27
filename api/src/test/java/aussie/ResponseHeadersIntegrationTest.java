package aussie;

import static io.restassured.RestAssured.given;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for cross-cutting response headers applied by the
 * Vert.x {@code @RouteFilter} chain (route resolution at priority 105, CORS
 * at priority 100, and security headers at priority 90).
 *
 * <p>Unit-level branch coverage lives in {@code CorsFilterTest} and
 * {@code SecurityHeadersFilterTest}. This test verifies the filters actually
 * run in the real Vert.x pipeline and emit headers on real HTTP responses,
 * which unit tests cannot prove.
 *
 * <p>Pins {@code aussie.auth.dangerous-noop=true} so admin endpoints return
 * 200 without credentials; the filters under test fire regardless of auth
 * outcome but the assertions on 200 responses depend on the call succeeding.
 */
@QuarkusTest
@TestProfile(ResponseHeadersIntegrationTest.NoopAuthProfile.class)
@DisplayName("Response Headers Integration Tests")
class ResponseHeadersIntegrationTest {

    public static class NoopAuthProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "aussie.auth.dangerous-noop", "true",
                    "aussie.gateway.cors.enabled", "true",
                    "aussie.gateway.cors.allowed-origins", "https://app.example.com",
                    "aussie.gateway.cors.allow-credentials", "true");
        }
    }

    @Nested
    @DisplayName("CORS Headers")
    class CorsHeaderTests {

        @Test
        @DisplayName("preflight OPTIONS should return CORS allow-* headers")
        void preflightShouldEmitCorsHeaders() {
            given().header("Origin", "https://app.example.com")
                    .header("Access-Control-Request-Method", "GET")
                    .when()
                    .options("/admin/services")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", "https://app.example.com")
                    .header("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("GET"))
                    .header("Access-Control-Allow-Credentials", "true");
        }

        @Test
        @DisplayName("non-preflight CORS request should echo Access-Control-Allow-Origin")
        void nonPreflightShouldEmitCorsHeaders() {
            given().header("Origin", "https://app.example.com")
                    .when()
                    .get("/admin/services")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", "https://app.example.com");
        }

        @Test
        @DisplayName("request without Origin header should not include CORS headers")
        void noOriginShouldNotEmitCorsHeaders() {
            given().when()
                    .get("/admin/services")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", org.hamcrest.Matchers.nullValue());
        }
    }

    @Nested
    @DisplayName("Security Headers")
    class SecurityHeaderTests {

        @Test
        @DisplayName("admin response should include OWASP security headers")
        void adminResponseShouldIncludeSecurityHeaders() {
            given().when()
                    .get("/admin/services")
                    .then()
                    .statusCode(200)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY")
                    .header("Content-Security-Policy", org.hamcrest.Matchers.notNullValue())
                    .header("Referrer-Policy", "strict-origin-when-cross-origin")
                    .header("X-Permitted-Cross-Domain-Policies", "none");
        }

        @Test
        @DisplayName("404 problem responses should still include security headers")
        void errorResponseShouldIncludeSecurityHeaders() {
            given().when()
                    .get("/gateway/no/such/route")
                    .then()
                    .statusCode(404)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY");
        }
    }
}
