package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.service.ServiceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
@DisplayName("Admin Resource Tests")
class AdminResourceTest {

    @Inject
    ServiceRegistry serviceRegistry;

    @AfterEach
    void tearDown() {
        serviceRegistry.getAllServices().forEach(s -> serviceRegistry.unregister(s.serviceId()));
    }

    @Nested
    @DisplayName("Service Registration")
    class ServiceRegistrationTests {

        @Test
        @DisplayName("Should register a new service")
        void shouldRegisterNewService() {
            var requestBody = """
                {
                    "serviceId": "test-service",
                    "displayName": "Test Service",
                    "baseUrl": "http://localhost:8081",
                    "endpoints": [
                        {
                            "path": "/api/test",
                            "methods": ["GET", "POST"],
                            "visibility": "PUBLIC"
                        }
                    ]
                }
                """;

            given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201)
                .body("serviceId", equalTo("test-service"))
                .body("displayName", equalTo("Test Service"))
                .body("baseUrl", equalTo("http://localhost:8081"))
                .body("endpoints", hasSize(1))
                .body("endpoints[0].path", equalTo("/api/test"))
                .body("endpoints[0].visibility", equalTo("PUBLIC"));
        }

        @Test
        @DisplayName("Should register service with private endpoints")
        void shouldRegisterServiceWithPrivateEndpoints() {
            var requestBody = """
                {
                    "serviceId": "private-service",
                    "displayName": "Private Service",
                    "baseUrl": "http://localhost:8082",
                    "endpoints": [
                        {
                            "path": "/api/public",
                            "methods": ["GET"],
                            "visibility": "PUBLIC"
                        },
                        {
                            "path": "/api/private",
                            "methods": ["GET", "POST"],
                            "visibility": "PRIVATE"
                        }
                    ],
                    "accessConfig": {
                        "allowedIps": ["10.0.0.0/8"],
                        "allowedDomains": ["internal.example.com"]
                    }
                }
                """;

            given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201)
                .body("serviceId", equalTo("private-service"))
                .body("endpoints", hasSize(2))
                .body("accessConfig.allowedIps[0]", equalTo("10.0.0.0/8"))
                .body("accessConfig.allowedDomains[0]", equalTo("internal.example.com"));
        }

        @Test
        @DisplayName("Should reject service with missing required fields")
        void shouldRejectServiceWithMissingFields() {
            var requestBody = """
                {
                    "displayName": "No ID Service"
                }
                """;

            given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(400);
        }
    }

    @Nested
    @DisplayName("Service Retrieval")
    class ServiceRetrievalTests {

        @Test
        @DisplayName("Should list all registered services")
        void shouldListAllServices() {
            // Register two services
            registerTestService("service-1", "http://localhost:8081");
            registerTestService("service-2", "http://localhost:8082");

            given()
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200)
                .body("$", hasSize(2));
        }

        @Test
        @DisplayName("Should get specific service by ID")
        void shouldGetServiceById() {
            registerTestService("my-service", "http://localhost:8083");

            given()
                .when()
                .get("/admin/services/my-service")
                .then()
                .statusCode(200)
                .body("serviceId", equalTo("my-service"))
                .body("baseUrl", equalTo("http://localhost:8083"));
        }

        @Test
        @DisplayName("Should return 404 for non-existent service")
        void shouldReturn404ForNonExistentService() {
            given()
                .when()
                .get("/admin/services/non-existent")
                .then()
                .statusCode(404);
        }
    }

    @Nested
    @DisplayName("Service Deletion")
    class ServiceDeletionTests {

        @Test
        @DisplayName("Should delete existing service")
        void shouldDeleteExistingService() {
            registerTestService("to-delete", "http://localhost:8084");

            given()
                .when()
                .delete("/admin/services/to-delete")
                .then()
                .statusCode(204);

            // Verify it's gone
            given()
                .when()
                .get("/admin/services/to-delete")
                .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("Should return 404 when deleting non-existent service")
        void shouldReturn404WhenDeletingNonExistent() {
            given()
                .when()
                .delete("/admin/services/non-existent")
                .then()
                .statusCode(404);
        }
    }

    private void registerTestService(String serviceId, String baseUrl) {
        var requestBody = String.format("""
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
            """, serviceId, baseUrl);

        given()
            .contentType(ContentType.JSON)
            .body(requestBody)
            .when()
            .post("/admin/services")
            .then()
            .statusCode(201);
    }
}
