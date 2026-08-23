package aussie;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.auth.RsaTokenIssuer;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
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

    @Inject
    RsaTokenIssuer tokenIssuer;

    @BeforeEach
    void seedActiveKey() {
        var pending = keyRegistry.generateAndRegisterKey().await().atMost(Duration.ofSeconds(10));
        keyRegistry.activateKey(pending.keyId()).await().atMost(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("should publish active signing key with cache headers")
    void shouldPublishActiveSigningKey() throws Exception {
        final var response = given().when()
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
                .body("keys[0].e", org.hamcrest.Matchers.notNullValue())
                .extract()
                .asString();

        final var token = tokenIssuer.issue(
                new TokenValidationResult.Valid(
                        "user-1",
                        "issuer",
                        Map.of("sub", "user-1"),
                        Instant.now().plusSeconds(300)),
                new JwsConfig(
                        "aussie-gateway",
                        "ignored",
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(5),
                        Set.of(),
                        Optional.of("downstream-services"),
                        true));
        final var jwks = new JsonWebKeySet(response);
        final var claims = new JwtConsumerBuilder()
                .setVerificationKey(jwks.getJsonWebKeys().stream()
                        .filter(key -> key.getKeyId()
                                .equals(keyRegistry.getCurrentSigningKey().keyId()))
                        .findFirst()
                        .orElseThrow()
                        .getKey())
                .setExpectedIssuer("aussie-gateway")
                .setSkipDefaultAudienceValidation()
                .build()
                .processToClaims(token.jws());

        assertEquals("user-1", claims.getSubject());
    }
}
