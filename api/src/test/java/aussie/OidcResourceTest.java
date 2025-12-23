package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the OidcResource PKCE endpoints.
 */
@QuarkusTest
@DisplayName("OIDC Resource Tests")
public class OidcResourceTest {

    private static final String IDP_URL = "https://idp.example.com/authorize";
    private static final String REDIRECT_URI = "http://localhost:3000/callback";

    @Nested
    @DisplayName("GET /auth/oidc/authorize")
    class AuthorizeTests {

        @Test
        @DisplayName("should reject request without code_challenge when PKCE required")
        void shouldRejectRequestWithoutCodeChallenge() {
            given().queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(400)
                    .body("title", equalTo("Bad Request"))
                    .body("detail", containsString("PKCE"));
        }

        @Test
        @DisplayName("should reject request with plain challenge method")
        void shouldRejectPlainChallengeMethod() {
            given().queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", "test-challenge")
                    .queryParam("code_challenge_method", "plain")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(400)
                    .body("title", equalTo("Bad Request"))
                    .body("detail", containsString("S256"));
        }

        @Test
        @DisplayName("should reject request without redirect_uri")
        void shouldRejectRequestWithoutRedirectUri() {
            given().queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", "test-challenge")
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should reject request without idp_url")
        void shouldRejectRequestWithoutIdpUrl() {
            given().queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("code_challenge", "test-challenge")
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should redirect to IdP with valid PKCE parameters")
        void shouldRedirectToIdpWithValidPkce() {
            String challenge = generateChallenge("test-verifier-12345678901234567890123456789012345");

            given().redirects()
                    .follow(false) // Don't follow redirects
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", challenge)
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(303)
                    .header("Location", containsString(IDP_URL))
                    .header("Location", containsString("state="))
                    .header("Location", containsString("redirect_uri="));
        }

        @Test
        @DisplayName("should include client state in redirect")
        void shouldIncludeClientStateInRedirect() {
            String challenge = generateChallenge("test-verifier-12345678901234567890123456789012345");

            given().redirects()
                    .follow(false)
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", challenge)
                    .queryParam("code_challenge_method", "S256")
                    .queryParam("state", "client-csrf-token")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(303)
                    .header("Location", containsString("client_state=client-csrf-token"));
        }
    }

    @Nested
    @DisplayName("POST /auth/oidc/token")
    class TokenExchangeTests {

        @Test
        @DisplayName("should reject request without code")
        void shouldRejectRequestWithoutCode() {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("state", "test-state")
                    .formParam("code_verifier", "test-verifier")
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should reject request without state")
        void shouldRejectRequestWithoutState() {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code")
                    .formParam("code_verifier", "test-verifier")
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should reject request without code_verifier when PKCE required")
        void shouldRejectRequestWithoutCodeVerifier() {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code")
                    .formParam("state", "test-state")
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(400)
                    .body("title", equalTo("Bad Request"))
                    .body("detail", containsString("code_verifier"));
        }

        @Test
        @DisplayName("should reject invalid code_verifier")
        void shouldRejectInvalidCodeVerifier() {
            // First, create an authorization request to store the challenge
            String verifier = "test-verifier-12345678901234567890123456789012345";
            String challenge = generateChallenge(verifier);

            String redirectLocation = given().redirects()
                    .follow(false)
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", challenge)
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(303)
                    .extract()
                    .header("Location");

            // Extract state from redirect URL
            String state = extractState(redirectLocation);

            // Try token exchange with wrong verifier
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code")
                    .formParam("state", state)
                    .formParam("code_verifier", "wrong-verifier-12345678901234567890123456789")
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(400)
                    .body("title", equalTo("Bad Request"))
                    .body("detail", containsString("PKCE verification failed"));
        }

        @Test
        @DisplayName("should accept valid code_verifier")
        void shouldAcceptValidCodeVerifier() {
            // First, create an authorization request to store the challenge
            String verifier = "valid-verifier-123456789012345678901234567890123";
            String challenge = generateChallenge(verifier);

            String redirectLocation = given().redirects()
                    .follow(false)
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", challenge)
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(303)
                    .extract()
                    .header("Location");

            // Extract state from redirect URL
            String state = extractState(redirectLocation);

            // Token exchange with correct verifier
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code")
                    .formParam("state", state)
                    .formParam("code_verifier", verifier)
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(200)
                    .body("message", notNullValue());
        }

        @Test
        @DisplayName("should reject replayed state (one-time use)")
        void shouldRejectReplayedState() {
            // First, create an authorization request
            String verifier = "replay-verifier-12345678901234567890123456789012";
            String challenge = generateChallenge(verifier);

            String redirectLocation = given().redirects()
                    .follow(false)
                    .queryParam("redirect_uri", REDIRECT_URI)
                    .queryParam("idp_url", IDP_URL)
                    .queryParam("code_challenge", challenge)
                    .queryParam("code_challenge_method", "S256")
                    .when()
                    .get("/auth/oidc/authorize")
                    .then()
                    .statusCode(303)
                    .extract()
                    .header("Location");

            String state = extractState(redirectLocation);

            // First token exchange should succeed
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code")
                    .formParam("state", state)
                    .formParam("code_verifier", verifier)
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(200);

            // Second token exchange with same state should fail (challenge consumed)
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("code", "test-code-2")
                    .formParam("state", state)
                    .formParam("code_verifier", verifier)
                    .when()
                    .post("/auth/oidc/token")
                    .then()
                    .statusCode(400)
                    .body("title", equalTo("Bad Request"));
        }
    }

    /**
     * Generate S256 challenge from verifier (same as server-side).
     */
    private String generateChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate challenge", e);
        }
    }

    /**
     * Extract state parameter from redirect URL.
     */
    private String extractState(String url) {
        int stateStart = url.indexOf("state=") + 6;
        int stateEnd = url.indexOf("&", stateStart);
        if (stateEnd == -1) {
            stateEnd = url.length();
        }
        return url.substring(stateStart, stateEnd);
    }
}
