package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke tests for API Key management endpoints.
 *
 * <p>Branch coverage (validation, permission filtering, error mapping) lives in
 * {@code ApiKeyResourceUnitTest}. This class only verifies real JAX-RS wiring,
 * JSON (de)serialization and the round-trip against the in-memory repository.
 */
@QuarkusTest
@DisplayName("API Key Resource Tests")
public class ApiKeyResourceTest {

    @Test
    @DisplayName("should create, retrieve and revoke API key end-to-end")
    void shouldRoundTripApiKey() {
        var keyId = given().contentType(ContentType.JSON)
                .body("""
                        {
                            "name": "round-trip-key",
                            "permissions": ["admin:read", "admin:write"]
                        }
                        """)
                .when()
                .post("/admin/api-keys")
                .then()
                .statusCode(201)
                .body("keyId", notNullValue())
                .body("key", notNullValue())
                .body("name", equalTo("round-trip-key"))
                .extract()
                .path("keyId");

        given().when()
                .get("/admin/api-keys/" + keyId)
                .then()
                .statusCode(200)
                .body("name", equalTo("round-trip-key"))
                .body("keyHash", equalTo("[REDACTED]"));

        given().when().delete("/admin/api-keys/" + keyId).then().statusCode(204);
    }

    @Test
    @DisplayName("should return 404 for non-existent key")
    void shouldReturn404ForNonExistentKey() {
        given().when().get("/admin/api-keys/nonexistent").then().statusCode(404);
    }
}
