package aussie;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@code /auth/.well-known/jwks.json} endpoint with
 * key rotation disabled (the default test profile state).
 *
 * <p>Branch coverage of the conversion logic lives in {@code JwksResourceTest}.
 * The "rotation enabled" path is exercised by {@link JwksRotationEnabledIntegrationTest}.
 */
@QuarkusTest
@DisplayName("JWKS Integration Tests")
class JwksIntegrationTest {

    @Test
    @DisplayName("should return empty key set when rotation disabled")
    void shouldReturnEmptyKeySet() {
        given().when()
                .get("/auth/.well-known/jwks.json")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("keys", org.hamcrest.Matchers.notNullValue())
                .body("keys.size()", org.hamcrest.Matchers.equalTo(0));
    }
}
