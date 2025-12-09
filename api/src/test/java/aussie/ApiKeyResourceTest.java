package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for API Key management endpoints.
 * Uses default test profile with dangerous-noop enabled.
 */
@QuarkusTest
@DisplayName("API Key Resource Tests")
public class ApiKeyResourceTest {

    @Test
    @DisplayName("should create API key and return plaintext key")
    void shouldCreateApiKey() {
        given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "name": "integration-test-key",
                        "description": "Created by integration test",
                        "permissions": ["admin:read", "admin:write"]
                    }
                    """)
                .when()
                .post("/admin/api-keys")
                .then()
                .statusCode(201)
                .body("keyId", notNullValue())
                .body("key", notNullValue())
                .body("name", equalTo("integration-test-key"));
    }

    @Test
    @DisplayName("should create API key with TTL")
    void shouldCreateApiKeyWithTtl() {
        given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "name": "expiring-key",
                        "permissions": ["admin:read"],
                        "ttlDays": 30
                    }
                    """)
                .when()
                .post("/admin/api-keys")
                .then()
                .statusCode(201)
                .body("keyId", notNullValue())
                .body("expiresAt", notNullValue());
    }

    @Test
    @DisplayName("should reject create without name")
    void shouldRejectCreateWithoutName() {
        given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "permissions": ["admin:read"]
                    }
                    """)
                .when()
                .post("/admin/api-keys")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("detail", equalTo("name is required"));
    }

    @Test
    @DisplayName("should list API keys")
    void shouldListApiKeys() {
        // Create a key first
        given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "name": "list-test-key",
                        "permissions": ["admin:read"]
                    }
                    """)
                .when()
                .post("/admin/api-keys");

        // List should return 200
        given().when().get("/admin/api-keys").then().statusCode(200);
    }

    @Test
    @DisplayName("should get API key by ID")
    void shouldGetApiKeyById() {
        // Create a key
        var keyId = given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "name": "get-test-key",
                        "permissions": ["admin:read"]
                    }
                    """)
                .when()
                .post("/admin/api-keys")
                .then()
                .extract()
                .path("keyId");

        // Get it
        given().when()
                .get("/admin/api-keys/" + keyId)
                .then()
                .statusCode(200)
                .body("name", equalTo("get-test-key"))
                .body("keyHash", equalTo("[REDACTED]"));
    }

    @Test
    @DisplayName("should return 404 for non-existent key")
    void shouldReturn404ForNonExistentKey() {
        given().when().get("/admin/api-keys/nonexistent").then().statusCode(404);
    }

    @Test
    @DisplayName("should revoke API key")
    void shouldRevokeApiKey() {
        // Create a key
        var keyId = given().contentType(ContentType.JSON)
                .body(
                        """
                    {
                        "name": "revoke-test-key",
                        "permissions": ["admin:read"]
                    }
                    """)
                .when()
                .post("/admin/api-keys")
                .then()
                .extract()
                .path("keyId");

        // Revoke it
        given().when().delete("/admin/api-keys/" + keyId).then().statusCode(204);
    }

    @Test
    @DisplayName("should return 404 when revoking non-existent key")
    void shouldReturn404ForNonExistentRevoke() {
        given().when().delete("/admin/api-keys/nonexistent").then().statusCode(404);
    }
}
