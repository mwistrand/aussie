package aussie;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.auth.RsaTokenIssuer;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;

/**
 * Proves static issuance and JWKS publication consume the same signing authority.
 */
@QuarkusTest
@TestProfile(JwksIntegrationTest.StaticSigningKeyProfile.class)
@DisplayName("JWKS Integration Tests")
class JwksIntegrationTest {

    public static class StaticSigningKeyProfile implements QuarkusTestProfile {

        private static final KeyPair KEY_PAIR = generateKeyPair();

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "aussie.auth.route-auth.jws.signing-key",
                    Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded()),
                    "aussie.auth.route-auth.jws.key-id",
                    "static-test-key");
        }

        private static KeyPair generateKeyPair() {
            try {
                final var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Inject
    RsaTokenIssuer tokenIssuer;

    @Test
    @DisplayName("static JWKS verifies the token issuer")
    void staticJwksVerifiesIssuer() throws Exception {
        final var response = given().when()
                .get("/auth/.well-known/jwks.json")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .header("ETag", org.hamcrest.Matchers.notNullValue())
                .body("keys", org.hamcrest.Matchers.hasSize(1))
                .extract()
                .asString();
        final var jwks = new JsonWebKeySet(response);
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

        final var claims = new JwtConsumerBuilder()
                .setVerificationKey(jwks.getJsonWebKeys().getFirst().getKey())
                .setExpectedIssuer("aussie-gateway")
                .setSkipDefaultAudienceValidation()
                .build()
                .processToClaims(token.jws());

        assertEquals("user-1", claims.getSubject());
    }
}
