package aussie;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.service.auth.SigningKeyRegistry;

/**
 * Integration test for the JWKS endpoint with key rotation enabled.
 *
 * <p>Seeds an active signing key via {@link SigningKeyRegistry} so the
 * endpoint emits a populated key set, which lets us assert both the JWK
 * shape and the {@code Cache-Control} header that downstream verifiers
 * rely on. Branch coverage of JWK conversion lives in {@code JwksResourceTest}.
 */
@QuarkusTest
@TestProfile(JwksRotationEnabledIntegrationTest.KeyRotationEnabledProfile.class)
@DisplayName("JWKS Integration Tests (Rotation Enabled)")
class JwksRotationEnabledIntegrationTest {

    public static class KeyRotationEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "aussie.auth.key-rotation.enabled", "true",
                    "aussie.auth.key-rotation.storage", "config",
                    "aussie.auth.key-rotation.key-size", "2048");
        }
    }

    @Inject
    SigningKeyRegistry keyRegistry;

    @BeforeEach
    void seedActiveKey() {
        var pending = keyRegistry.generateAndRegisterKey().await().atMost(Duration.ofSeconds(10));
        keyRegistry.activateKey(pending.keyId()).await().atMost(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("should publish active signing key with cache headers")
    void shouldPublishActiveSigningKey() {
        given().when()
                .get("/auth/.well-known/jwks.json")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .header("Cache-Control", org.hamcrest.Matchers.containsString("max-age="))
                .body("keys", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .body("keys[0].kty", org.hamcrest.Matchers.equalTo("RSA"))
                .body("keys[0].use", org.hamcrest.Matchers.equalTo("sig"))
                .body("keys[0].alg", org.hamcrest.Matchers.equalTo("RS256"))
                .body("keys[0].kid", org.hamcrest.Matchers.notNullValue())
                .body("keys[0].n", org.hamcrest.Matchers.notNullValue())
                .body("keys[0].e", org.hamcrest.Matchers.notNullValue());
    }
}
