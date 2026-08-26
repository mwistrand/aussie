package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.security.KeyPairGenerator;
import java.time.Instant;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke tests for token revocation endpoints.
 *
 * <p>Branch coverage (validation, error mapping, claim extraction) lives in
 * {@code adapter.in.rest.TokenRevocationResourceTest}. This class verifies real
 * round-trip behavior of revoking by JTI / by full JWT and inspecting tokens.
 */
@QuarkusTest
@DisplayName("Token Revocation Resource Tests")
public class TokenRevocationResourceTest {

    @Test
    @DisplayName("should revoke by JTI and reflect in status check")
    void shouldRevokeByJtiAndCheckStatus() {
        var jti = "round-trip-jti";

        given().contentType(ContentType.JSON)
                .body("{\"reason\": \"integration test\"}")
                .when()
                .delete("/admin/tokens/" + jti)
                .then()
                .statusCode(204);

        given().when()
                .get("/admin/tokens/" + jti + "/status")
                .then()
                .statusCode(200)
                .body("jti", equalTo(jti))
                .body("revoked", equalTo(true))
                .body("checkedAt", notNullValue());
    }

    @Test
    @DisplayName("should reject an unsigned full JWT before revocation")
    void shouldRejectUnsignedFullToken() throws Exception {
        var token = createUnsignedTestToken("forged-jti", "test-subject", "https://test.issuer");

        given().contentType(ContentType.JSON)
                .body("{\"token\": \"" + token + "\", \"reason\": \"test\"}")
                .when()
                .post("/admin/tokens/revoke")
                .then()
                .statusCode(400);

        given().when()
                .get("/admin/tokens/forged-jti/status")
                .then()
                .statusCode(200)
                .body("revoked", equalTo(false));
    }

    @Test
    @DisplayName("should inspect token and surface claims")
    void shouldInspectToken() throws Exception {
        var token = createTestToken("inspect-test-jti", "user@example.com", "https://auth.example.com");

        given().contentType(ContentType.JSON)
                .body("{\"token\": \"" + token + "\"}")
                .when()
                .post("/admin/tokens/inspect")
                .then()
                .statusCode(200)
                .body("jti", equalTo("inspect-test-jti"))
                .body("subject", equalTo("user@example.com"))
                .body("issuer", equalTo("https://auth.example.com"));
    }

    private String createTestToken(String jti, String subject, String issuer) throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        var claims = new JwtClaims();
        claims.setJwtId(jti);
        claims.setSubject(subject);
        claims.setIssuer(issuer);
        claims.setAudience("test-audience");
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());

        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setKeyIdHeaderValue("test-key");
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private String createUnsignedTestToken(String jti, String subject, String issuer) throws Exception {
        var claims = new JwtClaims();
        claims.setJwtId(jti);
        claims.setSubject(subject);
        claims.setIssuer(issuer);
        claims.setAudience("test-audience");
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());

        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setAlgorithmConstraints(AlgorithmConstraints.NO_CONSTRAINTS);
        jws.setAlgorithmHeaderValue("none");
        return jws.getCompactSerialization();
    }
}
