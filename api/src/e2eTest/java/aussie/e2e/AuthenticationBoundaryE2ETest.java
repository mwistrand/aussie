package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.SuiteContext;

@DisplayName("Packaged authentication boundaries")
final class AuthenticationBoundaryE2ETest {

    @Test
    @DisplayName("rejects unsigned session construction without issuing a cookie")
    void rejectsUnsignedSessionConstruction() {
        var token = unsignedToken("{\"sub\":\"attacker\",\"permissions\":[\"admin\"]}");

        Response response = given().baseUri(SuiteContext.get().gatewayBaseUri().toString())
                .contentType(ContentType.JSON)
                .body("{\"token\":\"" + token + "\"}")
                .when()
                .post("/auth/session");

        assertEquals(401, response.statusCode(), response.asString());
        assertNull(response.getHeader("Set-Cookie"), "invalid identity must not create a session");
    }

    @Test
    @DisplayName("rejects unsigned callback tokens without issuing a cookie")
    void rejectsUnsignedCallbackToken() {
        var token = unsignedToken("{\"sub\":\"attacker\",\"iss\":\"caller-controlled\"}");

        Response response = given().baseUri(SuiteContext.get().gatewayBaseUri().toString())
                .queryParam("token", token)
                .queryParam("redirect", "/")
                .when()
                .get("/auth/callback");

        assertEquals(401, response.statusCode(), response.asString());
        assertNull(response.getHeader("Set-Cookie"), "invalid identity must not create a session");
    }

    @Test
    @DisplayName("rejects an unregistered OIDC redirect before storing state")
    void rejectsUnregisteredOidcRedirect() throws Exception {
        var verifier = "e2e-verifier-123456789012345678901234567890123";
        var challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        given().baseUri(SuiteContext.get().gatewayBaseUri().toString())
                .queryParam("redirect_uri", "https://attacker.example/callback")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .when()
                .get("/auth/oidc/authorize")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("keeps protected upstream endpoints closed to anonymous callers")
    void rejectsAnonymousProtectedUpstreamRequest() {
        var ctx = SuiteContext.get();

        given().baseUri(ctx.gatewayBaseUri().toString())
                .when()
                .get("/{serviceId}/api/latency-test", ctx.demoServiceId())
                .then()
                .statusCode(401);
    }

    private static String unsignedToken(String claims) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        var header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        var payload = encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }
}
