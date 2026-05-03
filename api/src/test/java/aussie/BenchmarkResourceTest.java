package aussie;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the benchmark authorization endpoint.
 *
 * <p>The default test profile runs with {@code aussie.auth.dangerous-noop=true},
 * which grants wildcard permissions and effectively short-circuits the
 * {@code @PermissionsAllowed} check. The purpose of this test is to keep the
 * endpoint wired into the app and return 204 from the noop path.
 */
@QuarkusTest
@DisplayName("Benchmark Resource Tests")
class BenchmarkResourceTest {

    @Test
    @DisplayName("authorize endpoint should return 204 when caller is permitted")
    void shouldReturn204WhenAuthorized() {
        given().when().get("/admin/benchmark/authorize").then().statusCode(204);
    }
}
