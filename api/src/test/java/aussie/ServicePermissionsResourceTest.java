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
import org.junit.jupiter.api.Test;

import aussie.core.service.routing.ServiceRegistry;

/**
 * Integration smoke tests for service permissions endpoints.
 *
 * <p>Branch coverage (404, version mismatch, individual validation cases)
 * lives in {@code ServicePermissionsResourceUnitTest}. This class verifies the
 * real If-Match optimistic-locking round-trip end-to-end.
 */
@QuarkusTest
@DisplayName("Service Permissions Resource Tests")
class ServicePermissionsResourceTest {

    @Inject
    ServiceRegistry serviceRegistry;

    private static final String TEST_SERVICE_ID = "perm-test-service";

    @BeforeEach
    void setUp() {
        registerTestService(TEST_SERVICE_ID);
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

    @Test
    @DisplayName("should round-trip permission policy with optimistic locking")
    void shouldRoundTripPolicyWithOptimisticLocking() {
        var initialResponse = given().when()
                .get("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                .then()
                .statusCode(200)
                .body("version", notNullValue())
                .extract()
                .response();

        var version = initialResponse.jsonPath().getInt("version");

        var policy =
                """
                {
                    "permissions": {
                        "service.config.read": {
                            "anyOfPermissions": ["test-service.reader", "test-service.admin"]
                        }
                    }
                }
                """;

        given().contentType(ContentType.JSON)
                .header("If-Match", String.valueOf(version))
                .body(policy)
                .when()
                .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                .then()
                .statusCode(200)
                .body("permissionPolicy.permissions", hasKey("service.config.read"))
                .body("version", equalTo(version + 1));

        // Stale version must conflict
        given().contentType(ContentType.JSON)
                .header("If-Match", String.valueOf(version))
                .body(policy)
                .when()
                .put("/admin/services/" + TEST_SERVICE_ID + "/permissions")
                .then()
                .statusCode(409);
    }

    private void registerTestService(String serviceId) {
        var requestBody = String.format(
                """
                {
                    "serviceId": "%s",
                    "baseUrl": "http://backend.local:8080",
                    "endpoints": [
                        { "path": "/api/test", "methods": ["GET"], "visibility": "PUBLIC" }
                    ]
                }
                """,
                serviceId);

        given().contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201);
    }
}
