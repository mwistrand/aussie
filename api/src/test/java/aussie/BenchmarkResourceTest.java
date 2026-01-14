package aussie;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Benchmark authorization endpoint.
 *
 * <p>Uses default test profile with dangerous-noop enabled, which grants wildcard
 * permissions (effectively admin access).
 */
@QuarkusTest
@DisplayName("Benchmark Resource Tests")
class BenchmarkResourceTest {

    @Test
    @DisplayName("Should return 204 when user has benchmark permission")
    void shouldReturn204WhenAuthorized() {
        given().when().get("/admin/benchmark/authorize").then().statusCode(204);
    }
}
