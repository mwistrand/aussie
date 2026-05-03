package aussie;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Permission;
import aussie.core.port.in.ApiKeyManagement;

/**
 * Integration tests for API key-based authentication with auth actually enforced.
 *
 * <p>This test runs with {@code dangerous-noop=false} so the
 * {@code AuthenticationFilter} actually rejects unauthenticated and
 * insufficiently-permissioned requests. Branch coverage of the filter logic
 * lives in {@link aussie.system.filter.AuthenticationFilterTest}.
 */
@QuarkusTest
@TestProfile(AuthenticationIntegrationTest.AuthEnforcedProfile.class)
@DisplayName("API Key Authentication Tests")
public class AuthenticationIntegrationTest {

    public static class AuthEnforcedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "aussie.auth.dangerous-noop", "false",
                    "aussie.session.enabled", "false",
                    "aussie.auth.route-auth.enabled", "false",
                    "aussie.rate-limiting.enabled", "false",
                    "aussie.rate-limiting.redis.enabled", "false",
                    "aussie.auth.rate-limit.enabled", "false");
        }
    }

    @Inject
    ApiKeyManagement apiKeyService;

    private String validApiKey;
    private String readOnlyApiKey;
    private String validApiKeyId;
    private String readOnlyApiKeyId;

    @BeforeEach
    void setUp() {
        var fullAccessResult = apiKeyService
                .create("test-full", null, null, Set.of(Permission.ALL_VALUE), null, "test")
                .await()
                .atMost(Duration.ofSeconds(5));
        validApiKey = fullAccessResult.plaintextKey();
        validApiKeyId = fullAccessResult.keyId();

        var readOnlyResult = apiKeyService
                .create("test-readonly", null, null, Set.of(Permission.SERVICE_CONFIG_READ_VALUE), null, "test")
                .await()
                .atMost(Duration.ofSeconds(5));
        readOnlyApiKey = readOnlyResult.plaintextKey();
        readOnlyApiKeyId = readOnlyResult.keyId();
    }

    @AfterEach
    void tearDown() {
        if (validApiKeyId != null) {
            apiKeyService.revoke(validApiKeyId).await().atMost(Duration.ofSeconds(5));
        }
        if (readOnlyApiKeyId != null) {
            apiKeyService.revoke(readOnlyApiKeyId).await().atMost(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("should reject admin request without auth")
    void shouldRejectWithoutAuth() {
        given().when().get("/admin/services").then().statusCode(401);
    }

    @Test
    @DisplayName("should reject admin request with bogus bearer token")
    void shouldRejectInvalidKey() {
        given().header("Authorization", "Bearer not-a-real-key")
                .when()
                .get("/admin/services")
                .then()
                .statusCode(401);
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
    @DisplayName("should allow POST when key has write permission")
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
    @DisplayName("should reject POST when key only has read permission")
    void shouldRejectPostWithReadOnlyKey() {
        given().header("Authorization", "Bearer " + readOnlyApiKey)
                .contentType(ContentType.JSON)
                .body(
                        """
                        {
                            "serviceId": "denied-service",
                            "baseUrl": "http://backend.local:9999"
                        }
                        """)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(403);
    }

    @Test
    @DisplayName("anonymous gateway request reaches gateway routing without auth challenge")
    void gatewayPathsShouldNotRequireAuth() {
        // 404 (route not found) rather than 401 proves the auth filter let the
        // anonymous request through to the gateway, which then 404s on the
        // unmatched route.
        given().when().get("/gateway/api/test").then().statusCode(404);
    }

    @Test
    @DisplayName("anonymous pass-through request reaches the service router without auth challenge")
    void passThroughPathsShouldNotRequireAuth() {
        given().when().get("/test-service/api/test").then().statusCode(404);
    }
}
