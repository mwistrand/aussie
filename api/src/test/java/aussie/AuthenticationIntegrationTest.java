package aussie;

import static io.restassured.RestAssured.given;

import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Permission;
import aussie.core.port.in.ApiKeyManagement;

/**
 * Integration tests for API key-based authentication.
 *
 * <p>
 * Note: These tests run with dangerous-noop=true (default test config),
 * so they focus on testing API key functionality. The full auth flow
 * (rejecting unauthenticated requests) is tested in unit tests.
 *
 * @see aussie.system.filter.AuthenticationFilterTest
 * @see aussie.adapter.in.auth.ApiKeyAuthProviderTest
 */
@QuarkusTest
@DisplayName("API Key Authentication Tests")
public class AuthenticationIntegrationTest {

    @Inject
    ApiKeyManagement apiKeyService;

    private String validApiKey;
    private String readOnlyApiKey;

    @BeforeEach
    void setUp() {
        // Create API keys for testing
        // Use wildcard permission for full access (includes service-level authorization)
        var fullAccessResult = apiKeyService
                .create("test-full", null, Set.of(Permission.ALL_VALUE), null, "test")
                .await()
                .indefinitely();
        validApiKey = fullAccessResult.plaintextKey();

        var readOnlyResult = apiKeyService
                .create("test-readonly", null, Set.of(Permission.SERVICE_CONFIG_READ_VALUE), null, "test")
                .await()
                .indefinitely();
        readOnlyApiKey = readOnlyResult.plaintextKey();
    }

    @Test
    @DisplayName("should allow GET with valid API key")
    void shouldAllowGetWithValidApiKey() {
        given().header("Authorization", "Bearer " + validApiKey)
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("should allow POST with admin:write permission")
    void shouldAllowPostWithWritePermission() {
        given().header("Authorization", "Bearer " + validApiKey)
                .contentType(ContentType.JSON)
                .body(
                        """
                                {
                                    "serviceId": "auth-test-service",
                                    "displayName": "Auth Test Service",
                                    "baseUrl": "http://backend.local:9999"
                                }
                                """)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201);
    }

    @Test
    @DisplayName("should allow GET with read-only API key")
    void shouldAllowGetWithReadOnlyKey() {
        given().header("Authorization", "Bearer " + readOnlyApiKey)
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("gateway paths should not require authentication")
    void gatewayPathsShouldNotRequireAuth() {
        // Gateway paths return 404 (no service registered) but not 401
        given().when().get("/gateway/api/test").then().statusCode(404);
    }

    @Test
    @DisplayName("pass-through paths should not require authentication")
    void passThroughPathsShouldNotRequireAuth() {
        // Pass-through paths return 404 (no service registered) but not 401
        given().when().get("/test-service/api/test").then().statusCode(404);
    }

    @Test
    @DisplayName("whoami should return key info for authenticated user")
    void whoamiShouldReturnKeyInfo() {
        given().header("Authorization", "Bearer " + validApiKey)
                .when()
                .get("/admin/whoami")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("whoami should return permissions for authenticated user")
    void whoamiShouldReturnPermissions() {
        given().header("Authorization", "Bearer " + readOnlyApiKey)
                .when()
                .get("/admin/whoami")
                .then()
                .statusCode(200);
    }
}
