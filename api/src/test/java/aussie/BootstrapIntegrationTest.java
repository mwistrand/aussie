package aussie;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.Permission;
import aussie.core.port.in.ApiKeyManagement;
import aussie.core.port.out.ApiKeyRepository;

/**
 * Integration tests for bootstrap admin key functionality.
 *
 * <p>
 * These tests verify that the bootstrap mechanism correctly creates an
 * admin API key on startup and that the key can be used for authentication.
 */
@QuarkusTest
@TestProfile(BootstrapIntegrationTest.BootstrapEnabledProfile.class)
@DisplayName("Bootstrap Integration Tests")
public class BootstrapIntegrationTest {

    // Must be at least 32 characters
    static final String TEST_BOOTSTRAP_KEY = "test-bootstrap-key-for-integration-tests!";

    @Inject
    ApiKeyRepository repository;

    @Inject
    ApiKeyManagement apiKeyService;

    /**
     * Test profile that enables bootstrap with a test key.
     */
    public static class BootstrapEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("aussie.bootstrap.enabled", "true"),
                    Map.entry("aussie.bootstrap.key", TEST_BOOTSTRAP_KEY),
                    Map.entry("aussie.bootstrap.ttl", "PT1H"),
                    // Disable dangerous-noop so we can test actual authentication
                    Map.entry("aussie.auth.dangerous-noop", "false"),
                    // Session configuration (disabled for this test)
                    Map.entry("aussie.session.enabled", "false"),
                    Map.entry("aussie.session.ttl", "PT8H"),
                    Map.entry("aussie.session.idle-timeout", "PT30M"),
                    Map.entry("aussie.session.sliding-expiration", "true"),
                    Map.entry("aussie.session.id-generation.max-retries", "3"),
                    Map.entry("aussie.session.cookie.name", "aussie_session"),
                    Map.entry("aussie.session.cookie.path", "/"),
                    Map.entry("aussie.session.cookie.secure", "false"),
                    Map.entry("aussie.session.cookie.http-only", "true"),
                    Map.entry("aussie.session.cookie.same-site", "Lax"),
                    Map.entry("aussie.session.storage.provider", "memory"),
                    Map.entry("aussie.session.storage.redis.key-prefix", "aussie:session:"),
                    Map.entry("aussie.session.jws.enabled", "false"),
                    Map.entry("aussie.session.jws.ttl", "PT5M"),
                    Map.entry("aussie.session.jws.issuer", "aussie-gateway"),
                    Map.entry("aussie.session.jws.include-claims", "sub,email,name,roles"),
                    // Route auth disabled
                    Map.entry("aussie.auth.route-auth.enabled", "false"));
        }
    }

    @Test
    @DisplayName("should create bootstrap key on startup")
    void shouldCreateBootstrapKeyOnStartup() {
        // The bootstrap key should have been created during application startup
        var keys = repository.findAll().await().indefinitely();

        var bootstrapKey =
                keys.stream().filter(k -> k.name().equals("bootstrap-admin")).findFirst();

        assertTrue(bootstrapKey.isPresent(), "Bootstrap key should exist");
        assertTrue(
                bootstrapKey.get().permissions().contains(Permission.ALL),
                "Bootstrap key should have wildcard permission");
        assertNotNull(bootstrapKey.get().expiresAt(), "Bootstrap key should have expiration");
    }

    @Test
    @DisplayName("bootstrap key should be validated correctly")
    void bootstrapKeyShouldBeValidated() {
        var validated = apiKeyService.validate(TEST_BOOTSTRAP_KEY).await().indefinitely();

        assertTrue(validated.isPresent(), "Bootstrap key should validate successfully");
        assertTrue(
                validated.get().permissions().contains(Permission.ALL),
                "Validated key should have wildcard permission");
    }

    @Test
    @DisplayName("bootstrap key should authenticate GET requests to admin endpoints")
    void bootstrapKeyShouldAuthenticateGetRequests() {
        given().header("Authorization", "Bearer " + TEST_BOOTSTRAP_KEY)
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200);
    }

    @Test
    @DisplayName("bootstrap key should authenticate POST requests to admin endpoints")
    void bootstrapKeyShouldAuthenticatePostRequests() {
        given().header("Authorization", "Bearer " + TEST_BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body(
                        """
                                {
                                    "serviceId": "bootstrap-test-service",
                                    "displayName": "Bootstrap Test Service",
                                    "baseUrl": "http://localhost:9999"
                                }
                                """)
                .when()
                .post("/admin/services")
                .then()
                .statusCode(201);
    }

    @Test
    @DisplayName("requests without auth should be rejected")
    void requestsWithoutAuthShouldBeRejected() {
        given().when().get("/admin/services").then().statusCode(401);
    }

    @Test
    @DisplayName("requests with invalid key should be rejected")
    void requestsWithInvalidKeyShouldBeRejected() {
        given().header("Authorization", "Bearer invalid-key-that-does-not-exist")
                .when()
                .get("/admin/services")
                .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("bootstrap key should allow creating new API keys")
    void bootstrapKeyShouldAllowCreatingNewApiKeys() {
        given().header("Authorization", "Bearer " + TEST_BOOTSTRAP_KEY)
                .contentType(ContentType.JSON)
                .body(
                        """
                                {
                                    "name": "new-admin-key",
                                    "permissions": ["admin:read", "admin:write"]
                                }
                                """)
                .when()
                .post("/admin/api-keys")
                .then()
                .statusCode(201);
    }
}
