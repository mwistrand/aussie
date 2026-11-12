package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke tests for translation config endpoints.
 *
 * <p>Validation/branch coverage lives in {@code TranslationConfigResourceUnitTest}.
 * This class verifies real round-trip: upload → list → activate → fetch active.
 */
@QuarkusTest
@DisplayName("Translation Config Resource")
class TranslationConfigResourceTest {

    private static final String VALID_CONFIG_INACTIVE = """
            {
                "config": {
                    "version": 1,
                    "sources": [
                        { "name": "roles", "claim": "realm_access.roles", "type": "array" }
                    ],
                    "transforms": [],
                    "mappings": {
                        "roleToPermissions": { "admin": ["admin.*"] },
                        "directPermissions": {}
                    },
                    "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                },
                "comment": "Test config",
                "activate": false
            }
            """;

    @Test
    @DisplayName("should upload, activate and fetch active config end-to-end")
    void shouldRoundTripConfigActivation() {
        var versionId = given().contentType(ContentType.JSON)
                .body(VALID_CONFIG_INACTIVE)
                .when()
                .post("/admin/translation-config")
                .then()
                .statusCode(201)
                .body("comment", equalTo("Test config"))
                .extract()
                .path("id");

        given().when()
                .put("/admin/translation-config/" + versionId + "/activate")
                .then()
                .statusCode(204);

        given().when()
                .get("/admin/translation-config/active")
                .then()
                .statusCode(200)
                .body("id", equalTo(versionId))
                .body("active", equalTo(true));
    }

    @Test
    @DisplayName("should keep validation response fixture shape")
    void shouldMatchValidationContractFixture() throws java.io.IOException {
        var response = given().contentType(ContentType.JSON)
                .body("null")
                .when()
                .post("/admin/translation-config/validate")
                .then()
                .statusCode(200)
                .extract()
                .response();

        var expected = new JsonObject(
                Files.readString(Path.of("..", "contract-fixtures", "control-plane", "translation-validation.json")));
        assertEquals(expected.getMap(), new JsonObject(response.asString()).getMap());
    }
}
