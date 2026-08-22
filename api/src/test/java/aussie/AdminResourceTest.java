package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.service.routing.ServiceRegistry;

/**
 * Integration smoke tests for service admin endpoints.
 *
 * <p>Branch coverage (validation, defaults, individual error cases) lives in
 * {@code AdminResourceUnitTest}. This class verifies real CRUD round-trip and
 * the SSRF baseUrl guard — both regression hot-spots that need integration
 * coverage.
 */
@QuarkusTest
@DisplayName("Admin Resource Tests")
class AdminResourceTest {

    @Inject
    ServiceRegistry serviceRegistry;

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
    @DisplayName("should round-trip service registration: create, list, get, delete")
    void shouldRoundTripServiceLifecycle() {
        var requestBody =
                """
                {
                    "serviceId": "lifecycle-service",
                    "displayName": "Lifecycle Service",
                    "baseUrl": "http://backend.local:8081",
                    "endpoints": [
                        {
                            "path": "/api/test",
                            "methods": ["GET", "POST"],
                            "visibility": "PUBLIC"
                        }
                    ]
                }
                """;

        given().contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201)
                .body("serviceId", equalTo("lifecycle-service"))
                .body("endpoints", hasSize(1))
                .body("endpoints[0].path", equalTo("/api/test"));

        given().when().get("/admin/services").then().statusCode(200).body("$", hasSize(1));

        given().when()
                .get("/admin/services/lifecycle-service")
                .then()
                .statusCode(200)
                .body("baseUrl", equalTo("http://backend.local:8081"));

        given().when().delete("/admin/services/lifecycle-service").then().statusCode(204);

        given().when().get("/admin/services/lifecycle-service").then().statusCode(404);
    }

    @Test
    @DisplayName("should reject service with SSRF-prone baseUrl")
    void shouldRejectServiceWithBlockedBaseUrl() {
        var requestBody =
                """
                {
                    "serviceId": "ssrf-service",
                    "baseUrl": "http://169.254.169.254/metadata"
                }
                """;

        given().contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(400)
                .body(
                        "detail",
                        equalTo("baseUrl must not point to a loopback, link-local, site-local, or metadata address"));
    }
}
