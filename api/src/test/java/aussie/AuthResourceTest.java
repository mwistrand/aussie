package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the AuthResource /admin/whoami endpoint.
 *
 * <p>Note: These tests run with dangerous-noop=true (default test config),
 * so they test the endpoint behavior without actual API key authentication.
 * Full authentication flow tests are in {@link AuthenticationIntegrationTest}.
 *
 * @see AuthenticationIntegrationTest for API key authentication tests
 */
@QuarkusTest
@DisplayName("Auth Resource Tests")
public class AuthResourceTest {

    @Test
    @DisplayName("should return 200 for whoami endpoint")
    void shouldReturn200ForWhoami() {
        given().when().get("/admin/whoami").then().statusCode(200);
    }

    @Test
    @DisplayName("should include name in whoami response")
    void shouldIncludeNameInResponse() {
        given().when().get("/admin/whoami").then().statusCode(200).body("name", notNullValue());
    }

    @Test
    @DisplayName("should include roles in whoami response")
    void shouldIncludeRolesInResponse() {
        given().when().get("/admin/whoami").then().statusCode(200).body("roles", notNullValue());
    }
}
