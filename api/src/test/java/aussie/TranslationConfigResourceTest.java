package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@QuarkusTest
@DisplayName("Translation Config Resource")
class TranslationConfigResourceTest {

    private static final String VALID_CONFIG =
            """
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
                "comment": "Test config"
            }
            """;

    @Nested
    @DisplayName("POST /admin/translation-config")
    class Upload {

        @Test
        @DisplayName("should upload new config")
        void shouldUploadNewConfig() {
            given().contentType(ContentType.JSON)
                    .body(VALID_CONFIG)
                    .when()
                    .post("/admin/translation-config")
                    .then()
                    .statusCode(201)
                    .body("id", notNullValue())
                    .body("comment", equalTo("Test config"));
        }

        @Test
        @DisplayName("should reject null config")
        void shouldRejectNullConfig() {
            given().contentType(ContentType.JSON)
                    .body("{}")
                    .when()
                    .post("/admin/translation-config")
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("POST /admin/translation-config/validate")
    class Validate {

        @Test
        @DisplayName("should validate valid config")
        void shouldValidateValidConfig() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "version": 1,
                                "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                                "transforms": [],
                                "mappings": { "roleToPermissions": {}, "directPermissions": {} },
                                "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                            }
                            """)
                    .when()
                    .post("/admin/translation-config/validate")
                    .then()
                    .statusCode(200)
                    .body("valid", equalTo(true))
                    .body("errors", empty());
        }

        @Test
        @DisplayName("should return errors for invalid config")
        void shouldReturnErrorsForInvalidConfig() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "version": 0,
                                "sources": null,
                                "transforms": [],
                                "mappings": null
                            }
                            """)
                    .when()
                    .post("/admin/translation-config/validate")
                    .then()
                    .statusCode(200)
                    .body("valid", equalTo(false))
                    .body("errors", hasSize(3));
        }
    }

    @Nested
    @DisplayName("GET /admin/translation-config/active")
    class GetActive {

        @Test
        @DisplayName("should return active config")
        void shouldReturnActiveConfig() {
            // Upload a config with activate=true (default)
            given().contentType(ContentType.JSON)
                    .body(VALID_CONFIG)
                    .when()
                    .post("/admin/translation-config")
                    .then()
                    .statusCode(201);

            // Get active should return a config
            given().when()
                    .get("/admin/translation-config/active")
                    .then()
                    .statusCode(200)
                    .body("active", equalTo(true));
        }
    }

    @Nested
    @DisplayName("GET /admin/translation-config/{versionId}")
    class GetVersion {

        @Test
        @DisplayName("should return 404 for non-existent version")
        void shouldReturn404ForNonExistent() {
            given().when()
                    .get("/admin/translation-config/non-existent-id")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("should return version by id")
        void shouldReturnVersionById() {
            var uploadResponse = given().contentType(ContentType.JSON)
                    .body(VALID_CONFIG)
                    .when()
                    .post("/admin/translation-config")
                    .then()
                    .statusCode(201)
                    .extract()
                    .response();

            var versionId = uploadResponse.jsonPath().getString("id");

            given().when()
                    .get("/admin/translation-config/" + versionId)
                    .then()
                    .statusCode(200)
                    .body("id", equalTo(versionId));
        }
    }

    @Nested
    @DisplayName("GET /admin/translation-config")
    class ListVersions {

        @Test
        @DisplayName("should list versions with pagination")
        void shouldListVersionsWithPagination() {
            // Upload some configs
            given().contentType(ContentType.JSON).body(VALID_CONFIG).post("/admin/translation-config");
            given().contentType(ContentType.JSON).body(VALID_CONFIG).post("/admin/translation-config");

            given().queryParam("limit", 1)
                    .queryParam("offset", 0)
                    .when()
                    .get("/admin/translation-config")
                    .then()
                    .statusCode(200)
                    .body("$", hasSize(1));
        }
    }

    @Nested
    @DisplayName("PUT /admin/translation-config/{versionId}/activate")
    class ActivateVersion {

        @Test
        @DisplayName("should return 404 for non-existent version")
        void shouldReturn404ForNonExistent() {
            given().when()
                    .put("/admin/translation-config/non-existent-id/activate")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("should activate a version")
        void shouldActivateVersion() {
            // Upload without activation
            var uploadResponse = given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "config": {
                                    "version": 1,
                                    "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                                    "transforms": [],
                                    "mappings": { "roleToPermissions": {}, "directPermissions": {} },
                                    "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                                },
                                "activate": false
                            }
                            """)
                    .when()
                    .post("/admin/translation-config")
                    .then()
                    .statusCode(201)
                    .extract()
                    .response();

            var versionId = uploadResponse.jsonPath().getString("id");

            // Now activate
            given().when()
                    .put("/admin/translation-config/" + versionId + "/activate")
                    .then()
                    .statusCode(204);

            // Verify it's now active
            given().when()
                    .get("/admin/translation-config/active")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo(versionId));
        }
    }

    @Nested
    @DisplayName("DELETE /admin/translation-config/{versionId}")
    class DeleteVersion {

        @Test
        @DisplayName("should return 404 for non-existent version")
        void shouldReturn404ForNonExistent() {
            given().when()
                    .delete("/admin/translation-config/non-existent-id")
                    .then()
                    .statusCode(404);
        }
    }

    @Nested
    @DisplayName("POST /admin/translation-config/test")
    class TestTranslation {

        @Test
        @DisplayName("should test translation with provided config")
        void shouldTestWithProvidedConfig() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "config": {
                                    "version": 1,
                                    "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                                    "transforms": [],
                                    "mappings": {
                                        "roleToPermissions": { "admin": ["admin.read", "admin.write"] },
                                        "directPermissions": {}
                                    },
                                    "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                                },
                                "claims": { "roles": ["admin"] }
                            }
                            """)
                    .when()
                    .post("/admin/translation-config/test")
                    .then()
                    .statusCode(200)
                    .body("roles", hasSize(1))
                    .body("permissions", hasSize(2));
        }
    }
}
