package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.service.routing.ServiceRegistry;

@QuarkusTest
@DisplayName("Service Permissions Resource Tests")
class ServicePermissionsResourceTest {

    @Inject
    ServiceRegistry serviceRegistry;

    private static final String TEST_SERVICE_ID = "perm-test-service";

    @BeforeEach
    void setUp() {
        // Register a test service
        registerTestService(TEST_SERVICE_ID, "http://backend.local:8080");
    }

    @AfterEach
    void tearDown() {
        serviceRegistry
                .getAllServices()
                .await()
                .atMost(java.time.Duration.ofSeconds(5))
                .forEach(
                        s -> serviceRegistry.unregister(s.serviceId()).await().atMost(java.time.Duration.ofSeconds(5)));
    }

    @Nested
    @DisplayName("GET /admin/services/{serviceId}/permissions")
    class GetPermissionsTests {

        @Test
        @DisplayName("Should get permissions for existing service")
        void shouldGetPermissionsForExistingService() {
            given().when()
                    .get("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .body("version", notNullValue());
        }

        @Test
        @DisplayName("Should return 404 for non-existent service")
        void shouldReturn404ForNonExistentService() {
            given().when()
                    .get("/admin/services/non-existent/permissions")
                    .then()
                    .statusCode(404);
        }
    }

    @Nested
    @DisplayName("PUT /admin/services/{serviceId}/permissions")
    class UpdatePermissionsTests {

        @Test
        @DisplayName("Should update permissions with valid If-Match header")
        void shouldUpdatePermissionsWithValidIfMatch() {
            // First get the current version
            var response = given().when()
                    .get("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();

            int version = response.jsonPath().getInt("version");

            var policyBody =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader", "test-service.admin"]
                        },
                        "service.config.update": {
                            "anyOfPermissions": ["test-service.admin"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", String.valueOf(version))
                    .body(policyBody)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .body("permissionPolicy.permissions", hasKey("service.config.read"))
                    .body("permissionPolicy.permissions", hasKey("service.config.update"))
                    .body("version", equalTo(version + 1));
        }

        @Test
        @DisplayName("Should return 400 when If-Match header is missing")
        void shouldReturn400WhenIfMatchMissing() {
            var policyBody =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .body(policyBody)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("Should return 409 when version doesn't match")
        void shouldReturn409WhenVersionMismatch() {
            var policyBody =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", "9999")
                    .body(policyBody)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(409);
        }

        @Test
        @DisplayName("Should return 404 for non-existent service")
        void shouldReturn404ForNonExistentService() {
            var policyBody =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", "1")
                    .body(policyBody)
                    .when()
                    .put("/admin/services/non-existent/permissions")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("Should allow clearing permissions by sending null")
        void shouldAllowClearingPermissions() {
            // First set some permissions
            var response = given().when()
                    .get("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();

            int version = response.jsonPath().getInt("version");

            var policyBody =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", String.valueOf(version))
                    .body(policyBody)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200);

            // Now clear permissions by sending null
            int newVersion = version + 1;
            given().contentType(ContentType.JSON)
                    .header("If-Match", String.valueOf(newVersion))
                    .body("null")
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(newVersion + 1));
        }
    }

    @Nested
    @DisplayName("Optimistic Locking")
    class OptimisticLockingTests {

        @Test
        @DisplayName("Should increment version on each update")
        void shouldIncrementVersionOnEachUpdate() {
            // Get initial version
            var response = given().when()
                    .get("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .extract()
                    .response();

            int initialVersion = response.jsonPath().getInt("version");

            // First update
            var policyBody1 =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["reader-v1"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", String.valueOf(initialVersion))
                    .body(policyBody1)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(initialVersion + 1));

            // Second update
            var policyBody2 =
                    """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["reader-v2"]
                        }
                    }
                }
                """;

            given().contentType(ContentType.JSON)
                    .header("If-Match", String.valueOf(initialVersion + 1))
                    .body(policyBody2)
                    .when()
                    .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(initialVersion + 2));
        }
    }

    private void registerTestService(String serviceId, String baseUrl) {
        var requestBody = String.format(
                """
            {
                "serviceId": "%s",
                "baseUrl": "%s",
                "endpoints": [
                    {
                        "path": "/api/test",
                        "methods": ["GET"],
                        "visibility": "PUBLIC"
                    }
                ]
            }
            """,
                serviceId, baseUrl);

        given().contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201);
    }
}
