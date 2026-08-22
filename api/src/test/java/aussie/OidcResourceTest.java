package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration smoke tests for the OidcResource PKCE endpoints.
 *
 * <p>Branch coverage (validation, individual error paths) lives in
 * {@code OidcResourceUnitTest}. This class verifies the real PKCE round-trip
 * (authorize → one-time exchange) and replay-state rejection.
 */
@QuarkusTest
@DisplayName("OIDC Resource Tests")
public class OidcResourceTest {

    private static final String CALLER_SELECTED_IDP_URL = "https://attacker.example.com/authorize";
    private static final String CONFIGURED_IDP_URL = "https://idp.example.com/authorize";
    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    @Test
    @DisplayName("should reject authorize without PKCE parameters")
    void shouldRejectAuthorizeWithoutPkce() {
        given().queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("idp_url", CALLER_SELECTED_IDP_URL)
                .when()
                .get("/auth/oidc/authorize")
                .then()
                .statusCode(400)
                .body("title", equalTo("Bad Request"))
                .body("detail", containsString("PKCE"));
    }

    @Test
    @DisplayName("should consume valid PKCE state and require an ID token")
    void shouldConsumePkceStateAndRequireIdToken() {
        var verifier = "valid-verifier-123456789012345678901234567890123";
        var challenge = generateChallenge(verifier);

        var redirectLocation = given().redirects()
                .follow(false)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("idp_url", CALLER_SELECTED_IDP_URL)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/auth/oidc/authorize")
                .then()
                .statusCode(303)
                .header("Location", containsString(CONFIGURED_IDP_URL))
                .extract()
                .header("Location");

        var state = extractState(redirectLocation);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("code", "test-code")
                .formParam("state", state)
                .formParam("code_verifier", verifier)
                .when()
                .post("/auth/oidc/token")
                .then()
                .statusCode(502)
                .body("detail", containsString("missing ID token"));

        // Replayed state must be rejected (one-time use)
        given().contentType("application/x-www-form-urlencoded")
                .formParam("code", "test-code-2")
                .formParam("state", state)
                .formParam("code_verifier", verifier)
                .when()
                .post("/auth/oidc/token")
                .then()
                .statusCode(400);
    }

    private String generateChallenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate challenge", e);
        }
    }

    private String extractState(String url) {
        var stateStart = url.indexOf("state=") + 6;
        var stateEnd = url.indexOf("&", stateStart);
        if (stateEnd == -1) {
            stateEnd = url.length();
        }
        return url.substring(stateStart, stateEnd);
    }
}
