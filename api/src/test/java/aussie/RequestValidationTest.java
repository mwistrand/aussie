package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("Request Validation Tests")
class RequestValidationTest {

    @Nested
    @DisplayName("Request Size Limits")
    class RequestSizeLimitTests {

        @Test
        @DisplayName("Should accept request within body size limit")
        void shouldAcceptValidBodySize() {
            // Small body should be fine (will get 404 since no route, but not 413)
            given().contentType(ContentType.JSON)
                    .body("{\"test\": \"data\"}")
                    .when()
                    .post("/gateway/any/path")
                    .then()
                    .statusCode(404); // No route, but passed validation
        }

        @Test
        @DisplayName("Should reject request with oversized individual header")
        void shouldRejectOversizedHeader() {
            // Test config sets max header size to 4KB (4096 bytes)
            var largeHeaderValue = "x".repeat(5000);

            given().header("X-Large-Header", largeHeaderValue)
                    .when()
                    .get("/gateway/any/path")
                    .then()
                    .statusCode(431)
                    .body(containsString("exceeds maximum"));
        }

        @Test
        @DisplayName("Should reject request with oversized total headers")
        void shouldRejectOversizedTotalHeaders() {
            // Test config sets max total headers to 16KB (16384 bytes)
            // Add multiple headers that together exceed the limit
            var headerValue = "y".repeat(3000);

            given().header("X-Header-1", headerValue)
                    .header("X-Header-2", headerValue)
                    .header("X-Header-3", headerValue)
                    .header("X-Header-4", headerValue)
                    .header("X-Header-5", headerValue)
                    .header("X-Header-6", headerValue)
                    .when()
                    .get("/gateway/any/path")
                    .then()
                    .statusCode(431)
                    .body(containsString("exceeds maximum"));
        }
    }
}
