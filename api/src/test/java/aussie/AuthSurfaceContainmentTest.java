package aussie;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AuthSurfaceContainmentTest.ContainedAuthSurfaceProfile.class)
@DisplayName("Authentication surface containment")
public class AuthSurfaceContainmentTest {

    public static class ContainedAuthSurfaceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "aussie.session.enabled", "true",
                    "aussie.session.public-creation-enabled", "false",
                    "aussie.auth.oidc.public-endpoints-enabled", "false",
                    "aussie.auth.pkce.enabled", "true");
        }
    }

    @Test
    @DisplayName("normal-mode flags reject every legacy identity-construction endpoint")
    void shouldRejectLegacyIdentityConstructionEndpoints() {
        final var createStatus = given().contentType(ContentType.JSON)
                .body("""
                        {
                          "userId": "attacker",
                          "issuer": "caller-controlled",
                          "permissions": ["admin"]
                        }
                        """)
                .when()
                .post("/auth/session")
                .statusCode();

        final var forgedToken = unsignedToken("""
                {"sub":"attacker","iss":"caller-controlled","permissions":["admin"]}
                """);
        final var callbackStatus = given().queryParam("token", forgedToken)
                .queryParam("redirect", "/")
                .when()
                .get("/auth/callback")
                .statusCode();

        final var authorizeStatus = given().queryParam("redirect_uri", "https://app.example.com/callback")
                .queryParam("idp_url", "https://attacker.example.com/authorize")
                .queryParam("code_challenge", "challenge")
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/auth/oidc/authorize")
                .statusCode();

        final var tokenStatus = given().contentType("application/x-www-form-urlencoded")
                .formParam("code", "caller-code")
                .formParam("state", "caller-state")
                .formParam("code_verifier", "caller-verifier")
                .when()
                .post("/auth/oidc/token")
                .statusCode();

        assertEquals(404, createStatus);
        assertEquals(404, callbackStatus);
        assertEquals(404, authorizeStatus);
        assertEquals(404, tokenStatus);
    }

    private static String unsignedToken(String claims) {
        final var encoder = Base64.getUrlEncoder().withoutPadding();
        final var header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        final var payload = encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
