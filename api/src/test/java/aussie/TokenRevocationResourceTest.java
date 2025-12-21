package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import java.security.KeyPairGenerator;
import java.time.Instant;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for token revocation endpoints.
 * Uses default test profile with dangerous-noop enabled.
 */
@QuarkusTest
@DisplayName("Token Revocation Resource Tests")
public class TokenRevocationResourceTest {

    @Test
    @DisplayName("should revoke token by JTI")
    void shouldRevokeTokenByJti() {
        given().contentType(ContentType.JSON)
                .body("{\"reason\": \"test revocation\"}")
                .when()
                .delete("/admin/tokens/test-jti-123")
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("should revoke token without request body")
    void shouldRevokeTokenWithoutBody() {
        given().contentType(ContentType.JSON)
                .when()
                .delete("/admin/tokens/test-jti-no-body")
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("should check revocation status")
    void shouldCheckRevocationStatus() {
        var jti = "status-check-jti";
        given().contentType(ContentType.JSON)
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
    @DisplayName("should return not revoked for unknown JTI")
    void shouldReturnNotRevokedForUnknownJti() {
        given().when()
                .get("/admin/tokens/unknown-jti-xyz/status")
                .then()
                .statusCode(200)
                .body("revoked", equalTo(false));
    }

    @Test
    @DisplayName("should list revoked tokens")
    void shouldListRevokedTokens() {
        var jti = "list-test-jti";
        given().contentType(ContentType.JSON).when().delete("/admin/tokens/" + jti);

        given().when()
                .get("/admin/tokens")
                .then()
                .statusCode(200)
                .body("revokedTokens", hasItem(jti))
                .body("count", greaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("should list revoked tokens with limit")
    void shouldListRevokedTokensWithLimit() {
        given().when().get("/admin/tokens?limit=5").then().statusCode(200).body("limit", equalTo(5));
    }

    @Test
    @DisplayName("should revoke all tokens for user")
    void shouldRevokeAllTokensForUser() {
        given().contentType(ContentType.JSON)
                .body("{\"reason\": \"logout everywhere\"}")
                .when()
                .delete("/admin/tokens/users/user-456")
                .then()
                .statusCode(204);
    }

    @Test
    @DisplayName("should list revoked users")
    void shouldListRevokedUsers() {
        var userId = "list-user-test";
        given().contentType(ContentType.JSON).when().delete("/admin/tokens/users/" + userId);

        given().when().get("/admin/tokens/users").then().statusCode(200).body("revokedUsers", hasItem(userId));
    }

    @Test
    @DisplayName("should trigger bloom filter rebuild")
    void shouldTriggerBloomFilterRebuild() {
        given().when()
                .post("/admin/tokens/bloom-filter/rebuild")
                .then()
                .statusCode(200)
                .body("status", equalTo("rebuilt"))
                .body("rebuiltAt", notNullValue());
    }

    @Nested
    @DisplayName("POST /admin/tokens/revoke")
    class RevokeByTokenTests {

        @Test
        @DisplayName("should revoke token by full JWT and return extracted JTI")
        void shouldRevokeByFullToken() throws Exception {
            var token = createTestToken("revoke-by-token-jti", "test-subject", "https://test.issuer");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\", \"reason\": \"test\"}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(200)
                    .body("jti", equalTo("revoke-by-token-jti"))
                    .body("status", equalTo("revoked"))
                    .body("revokedAt", notNullValue());
        }

        @Test
        @DisplayName("should revoke token without reason")
        void shouldRevokeByFullTokenWithoutReason() throws Exception {
            var token = createTestToken("revoke-no-reason-jti", "test-subject", "https://test.issuer");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\"}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(200)
                    .body("jti", equalTo("revoke-no-reason-jti"));
        }

        @Test
        @DisplayName("should return 400 for token without JTI claim")
        void shouldRejectTokenWithoutJti() throws Exception {
            var token = createTestTokenWithoutJti("test-subject", "https://test.issuer");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\"}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should return 400 for invalid token format")
        void shouldRejectInvalidTokenFormat() {
            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"not-a-valid-jwt\"}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should return 400 for empty token")
        void shouldRejectEmptyToken() {
            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"\"}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should return 400 for missing token")
        void shouldRejectMissingToken() {
            given().contentType(ContentType.JSON)
                    .body("{}")
                    .when()
                    .post("/admin/tokens/revoke")
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("POST /admin/tokens/inspect")
    class InspectTokenTests {

        @Test
        @DisplayName("should return all token claims")
        void shouldReturnTokenClaims() throws Exception {
            var token = createTestToken("inspect-test-jti", "user@example.com", "https://auth.example.com");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\"}")
                    .when()
                    .post("/admin/tokens/inspect")
                    .then()
                    .statusCode(200)
                    .body("jti", equalTo("inspect-test-jti"))
                    .body("subject", equalTo("user@example.com"))
                    .body("issuer", equalTo("https://auth.example.com"))
                    .body("expiresAt", notNullValue())
                    .body("issuedAt", notNullValue());
        }

        @Test
        @DisplayName("should return token with custom claims")
        void shouldReturnCustomClaims() throws Exception {
            var token = createTestTokenWithCustomClaims(
                    "custom-claims-jti", "test-user", "https://issuer.com", "admin", "org-123");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\"}")
                    .when()
                    .post("/admin/tokens/inspect")
                    .then()
                    .statusCode(200)
                    .body("jti", equalTo("custom-claims-jti"))
                    .body("otherClaims.role", equalTo("admin"))
                    .body("otherClaims.org_id", equalTo("org-123"));
        }

        @Test
        @DisplayName("should return empty list for missing audience")
        void shouldHandleMissingOptionalClaims() throws Exception {
            var token = createMinimalTestToken("minimal-jti");

            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"" + token + "\"}")
                    .when()
                    .post("/admin/tokens/inspect")
                    .then()
                    .statusCode(200)
                    .body("jti", equalTo("minimal-jti"))
                    .body("audience", empty());
        }

        @Test
        @DisplayName("should return 400 for invalid token format")
        void shouldRejectInvalidToken() {
            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"invalid.token\"}")
                    .when()
                    .post("/admin/tokens/inspect")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should return 400 for empty token")
        void shouldRejectEmptyToken() {
            given().contentType(ContentType.JSON)
                    .body("{\"token\": \"\"}")
                    .when()
                    .post("/admin/tokens/inspect")
                    .then()
                    .statusCode(400);
        }
    }

    // Helper methods for creating test tokens

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

    private String createTestTokenWithoutJti(String subject, String issuer) throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        var claims = new JwtClaims();
        claims.setSubject(subject);
        claims.setIssuer(issuer);
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

    private String createTestTokenWithCustomClaims(String jti, String subject, String issuer, String role, String orgId)
            throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        var claims = new JwtClaims();
        claims.setJwtId(jti);
        claims.setSubject(subject);
        claims.setIssuer(issuer);
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());
        claims.setClaim("role", role);
        claims.setClaim("org_id", orgId);

        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setKeyIdHeaderValue("test-key");
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private String createMinimalTestToken(String jti) throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        var claims = new JwtClaims();
        claims.setJwtId(jti);
        claims.setSubject("minimal-subject");
        claims.setIssuer("minimal-issuer");
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));

        var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setKeyIdHeaderValue("test-key");
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }
}
