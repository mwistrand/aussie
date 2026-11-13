package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.dto.CreateApiKeyRequest;
import aussie.adapter.in.dto.CreateRoleRequest;
import aussie.adapter.in.dto.ServicePermissionPolicyDto;
import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.adapter.in.dto.TranslationConfigUploadDto;
import aussie.adapter.in.dto.TranslationConfigVersionDto;
import aussie.adapter.in.dto.TranslationConfigVersionSummaryDto;
import aussie.adapter.in.dto.TranslationTestRequestDto;
import aussie.adapter.in.dto.UpdateRoleRequest;
import aussie.adapter.in.http.SessionResource.CreateSessionRequest;
import aussie.adapter.in.rest.ControlPlaneContract;
import aussie.adapter.in.rest.LockoutResource.ClearAllLockoutsRequest;
import aussie.adapter.in.rest.LockoutResource.ClearLockoutRequest;
import aussie.adapter.in.rest.ServicePermissionsResource.PermissionPolicyResponse;
import aussie.adapter.in.rest.SigningKeyResource;
import aussie.adapter.in.rest.TokenRevocationResource;
import aussie.core.model.auth.Role;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.routing.RoutingSnapshotStatus;

@QuarkusTest
class ControlPlaneContractTest {

    @Test
    void exposesCapabilitiesAndContractVersion() {
        var response = given().when()
                .get("/admin/capabilities")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header(ControlPlaneContract.VERSION_HEADER, equalTo(ControlPlaneContract.VERSION))
                .body("contractVersion", equalTo(ControlPlaneContract.VERSION))
                .body("supportedContractVersions[0]", equalTo(ControlPlaneContract.VERSION))
                .body("openApi", equalTo(ControlPlaneContract.SPECIFICATION_PATH))
                .extract()
                .response();

        assertEquals(fixture("capabilities.json").getMap(), new JsonObject(response.asString()).getMap());
    }

    @Test
    void rejectsUnsupportedControlPlaneVersion() {
        var response = given().header(ControlPlaneContract.VERSION_HEADER, "v99")
                .when()
                .get("/admin/capabilities")
                .then()
                .statusCode(406)
                .body("supportedVersion", equalTo(ControlPlaneContract.VERSION))
                .body("requestedVersion", equalTo("v99"))
                .extract()
                .response();

        assertEquals(
                new JsonObject("{\"supportedVersion\":\"v1\",\"requestedVersion\":\"v99\"}").getMap(),
                new JsonObject(response.asString()).getMap());
    }

    @Test
    void publishesTheCheckedInOpenApiContract() {
        given().when()
                .get(ControlPlaneContract.SPECIFICATION_PATH)
                .then()
                .statusCode(200)
                .contentType(org.hamcrest.Matchers.containsString("yaml"))
                .body(org.hamcrest.Matchers.containsString("openapi: 3.0.3"))
                .body(org.hamcrest.Matchers.containsString("/admin/services:"));
    }

    @Test
    void endpointFixtureMatrixCoversEveryPublishedOperation() throws java.io.IOException {
        var matrix = fixtureObject("endpoint-matrix.json");
        var specification =
                Files.readString(Path.of("src", "main", "resources", "META-INF", "resources", "openapi.yaml"));
        var documented = new HashSet<String>();
        var documentedRequestBodies = new HashSet<String>();
        var inPaths = false;
        String currentPath = null;
        String currentOperation = null;
        for (var line : specification.lines().toList()) {
            if (line.equals("paths:")) {
                inPaths = true;
                continue;
            }
            if (line.equals("components:")) {
                inPaths = false;
            }
            if (!inPaths) {
                continue;
            }
            if (line.startsWith("  /") && line.endsWith(":")) {
                currentPath = line.substring(2, line.length() - 1);
                currentOperation = null;
            } else if (currentPath != null && line.matches("    (get|post|put|delete|patch):")) {
                currentOperation = line.substring(4, line.length() - 1).toUpperCase() + " " + currentPath;
                documented.add(currentOperation);
            } else if (currentOperation != null && line.trim().startsWith("requestBody:")) {
                documentedRequestBodies.add(currentOperation);
            }
        }

        var matrixOperations = new HashSet<String>();
        for (var value : matrix.getJsonArray("operations")) {
            var operation = (JsonObject) value;
            var key = operation.getString("method") + " " + operation.getString("path");
            assertTrue(matrixOperations.add(key), "duplicate operation in fixture matrix: " + key);
            assertTrue(operation.getInteger("successStatus") >= 200, "missing success status: " + key);
            var requestFixture = operation.getString("requestFixture");
            var requestForm = operation.getJsonObject("requestForm");
            assertEquals(
                    documentedRequestBodies.contains(key),
                    requestFixture != null || requestForm != null,
                    "request fixture missing for " + key);
            if (requestForm != null) {
                assertEquals("application/x-www-form-urlencoded", operation.getString("requestMediaType"), key);
                assertOidcForm(operation, requestForm);
            }
            assertRequestFixture(key, requestFixture);
            assertFixtureIfPresent(operation.getString("responseFixture"));
            assertResponseFixture(key, operation.getString("responseFixture"));
        }

        assertEquals(documented, matrixOperations);
    }

    @Test
    void whoamiMatchesTheSharedFixture() {
        var response = given().when()
                .get("/admin/whoami")
                .then()
                .statusCode(200)
                .extract()
                .response();

        var expected = fixture("whoami.json");
        var actual = new JsonObject(response.asString());
        assertEquals(expected.getString("name"), actual.getString("name"));
        assertEquals(
                Set.copyOf(expected.getJsonArray("permissions").getList()),
                Set.copyOf(actual.getJsonArray("permissions").getList()));
        assertEquals(
                Set.copyOf(expected.getJsonArray("roles").getList()),
                Set.copyOf(actual.getJsonArray("roles").getList()));
    }

    private JsonObject fixture(String name) {
        return fixtureObject(name);
    }

    private JsonObject fixtureObject(String name) {
        try {
            return new JsonObject(Files.readString(Path.of("..", "contract-fixtures", "control-plane", name)));
        } catch (java.io.IOException e) {
            throw new AssertionError("unable to read control-plane contract fixture", e);
        }
    }

    private void assertRequestFixture(String operation, String name) {
        if (name == null) {
            return;
        }
        var content = fixtureContent(name);
        switch (operation) {
            case "POST /admin/services" -> {
                var request = decode(content, ServiceRegistrationRequest.class, name);
                assertNotNull(request.serviceId());
                assertNotNull(request.baseUrl());
                assertNotNull(request.endpoints());
            }
            case "PUT /admin/services/{serviceId}/permissions" -> {
                var request = decode(content, ServicePermissionPolicyDto.class, name);
                assertNotNull(request.permissions());
            }
            case "POST /admin/roles" ->
                assertNotNull(decode(content, CreateRoleRequest.class, name).id());
            case "PUT /admin/roles/{roleId}" -> assertNotNull(decode(content, UpdateRoleRequest.class, name));
            case "POST /admin/api-keys" ->
                assertNotNull(decode(content, CreateApiKeyRequest.class, name).name());
            case "POST /admin/keys/rotate" ->
                assertNotNull(decode(content, SigningKeyResource.RotateKeyRequest.class, name));
            case "DELETE /admin/tokens/{jti}" ->
                assertNotNull(decode(content, TokenRevocationResource.RevokeTokenRequest.class, name));
            case "POST /admin/tokens/revoke" -> {
                var request = decode(content, TokenRevocationResource.RevokeByTokenRequest.class, name);
                assertParseableJwt(request.token());
            }
            case "DELETE /admin/tokens/users/{userId}" ->
                assertNotNull(decode(content, TokenRevocationResource.RevokeUserTokensRequest.class, name));
            case "POST /admin/tokens/inspect" -> {
                var request = decode(content, TokenRevocationResource.InspectTokenRequest.class, name);
                assertParseableJwt(request.token());
            }
            case "POST /admin/translation-config" -> {
                var request = decode(content, TranslationConfigUploadDto.class, name);
                assertValidTranslationConfig(request.config());
            }
            case "POST /admin/translation-config/validate" ->
                assertValidTranslationConfig(decode(content, TranslationConfigSchema.class, name));
            case "POST /admin/translation-config/test" -> {
                var request = decode(content, TranslationTestRequestDto.class, name);
                assertValidTranslationConfig(request.config());
                assertNotNull(request.claims());
            }
            case "POST /admin/lockouts:reset" -> {
                var request = decode(content, ClearAllLockoutsRequest.class, name);
                assertTrue(request.force());
            }
            case "DELETE /admin/lockouts/ips/{ip}",
                    "DELETE /admin/lockouts/users/{identifier}",
                    "DELETE /admin/lockouts/apikeys/{keyPrefix}" ->
                assertNotNull(decode(content, ClearLockoutRequest.class, name));
            case "POST /auth/session" ->
                assertNotNull(decode(content, CreateSessionRequest.class, name).token());
            default -> objectFixture(content, name);
        }
    }

    private void assertResponseFixture(String operation, String name) {
        if (name == null) {
            return;
        }
        var content = fixtureContent(name);
        switch (operation) {
            case "GET /admin/capabilities" ->
                requireFields(content, name, "contractVersion", "supportedContractVersions", "openApi");
            case "GET /admin/whoami" -> requireFields(content, name, "name", "permissions", "roles");
            case "GET /admin/services" -> arrayFixture(content, name);
            case "POST /admin/services", "GET /admin/services/{serviceId}" -> {
                var response = decode(content, ServiceRegistrationResponse.class, name);
                assertNotNull(response.serviceId());
                assertNotNull(response.endpoints());
                var endpoint = response.endpoints().get(0);
                assertNotNull(endpoint.type());
                assertNotNull(endpoint.rateLimitConfig());
                assertNotNull(response.cors());
                assertNotNull(response.cors().allowedMethods());
                assertNotNull(response.cors().allowedHeaders());
                assertNotNull(response.cors().exposedHeaders());
                assertNotNull(response.cors().allowCredentials());
                assertNotNull(response.cors().maxAge());
            }
            case "GET /admin/services/routing-status" -> {
                requireFields(
                        content,
                        name,
                        "activeGeneration",
                        "durableGeneration",
                        "convergenceLag",
                        "checksum",
                        "pendingPropagations");
                assertNotNull(decode(content, RoutingSnapshotStatus.class, name));
            }
            case "GET /admin/services/{serviceId}/permissions", "PUT /admin/services/{serviceId}/permissions" ->
                assertNotNull(decode(content, PermissionPolicyResponse.class, name));
            case "GET /admin/roles" -> arrayFixture(content, name);
            case "POST /admin/roles", "GET /admin/roles/{roleId}", "PUT /admin/roles/{roleId}" ->
                assertNotNull(decode(content, Role.class, name));
            case "GET /admin/api-keys" -> arrayFixture(content, name);
            case "POST /admin/api-keys" ->
                requireFields(content, name, "keyId", "key", "name", "permissions", "createdBy");
            case "GET /admin/api-keys/{keyId}" ->
                requireFields(
                        content, name, "id", "name", "description", "permissions", "createdBy", "createdAt", "revoked");
            case "GET /admin/keys" -> arrayFixture(content, name);
            case "GET /admin/keys/{keyId}" ->
                assertNotNull(decode(content, SigningKeyResource.KeyDetailResponse.class, name));
            case "POST /admin/keys/rotate" -> {
                var response = decode(content, SigningKeyResource.KeySummaryResponse.class, name);
                assertEquals("ACTIVE", response.status().name());
                assertFalse(objectFixture(content, name).containsKey("previousKeyId"));
            }
            case "GET /admin/keys/health" ->
                assertNotNull(decode(content, SigningKeyResource.HealthResponse.class, name));
            case "GET /admin/tokens" -> requireFields(content, name, "revokedTokens", "count", "limit");
            case "POST /admin/tokens/revoke" -> requireFields(content, name, "jti", "status", "revokedAt");
            case "GET /admin/tokens/{jti}/status" -> requireFields(content, name, "jti", "revoked", "checkedAt");
            case "GET /admin/tokens/users" -> requireFields(content, name, "revokedUsers", "count", "limit");
            case "POST /admin/tokens/inspect" ->
                requireFields(
                        content, name, "jti", "subject", "issuer", "audience", "issuedAt", "expiresAt", "notBefore");
            case "POST /admin/tokens/bloom-filter/rebuild" -> requireFields(content, name, "status", "rebuiltAt");
            case "GET /admin/translation-config" -> {
                var versions = arrayFixture(content, name);
                for (var version : versions) {
                    Json.decodeValue(Json.encode(version), TranslationConfigVersionSummaryDto.class);
                }
            }
            case "POST /admin/translation-config",
                    "GET /admin/translation-config/{versionId}",
                    "GET /admin/translation-config/active",
                    "POST /admin/translation-config/rollback/{versionNumber}" ->
                assertNotNull(
                        decode(content, TranslationConfigVersionDto.class, name).config());
            case "POST /admin/translation-config/validate" -> requireFields(content, name, "valid", "errors");
            case "POST /admin/translation-config/test" -> requireFields(content, name, "roles", "permissions");
            case "GET /admin/translation-config/status" ->
                requireFields(content, name, "enabled", "activeProvider", "providerHealthy", "cache");
            case "GET /admin/lockouts" -> requireFields(content, name, "lockouts", "count", "limit");
            case "GET /admin/lockouts/ips/{ip}",
                    "GET /admin/lockouts/users/{identifier}",
                    "GET /admin/lockouts/apikeys/{keyPrefix}" ->
                requireFields(content, name, "type", "value", "key", "lockedOut");
            case "POST /admin/lockouts:reset" -> requireFields(content, name, "status", "count", "clearedAt");
            case "GET /auth/.well-known/jwks.json" -> requireFields(content, name, "keys");
            case "POST /auth/oidc/token" -> requireFields(content, name, "access_token", "token_type", "expires_in");
            case "POST /auth/session" -> requireFields(content, name, "userId", "expiresAt");
            case "GET /auth/session" ->
                requireFields(
                        content, name, "userId", "issuer", "permissions", "createdAt", "expiresAt", "lastAccessedAt");
            case "DELETE /auth/session", "DELETE /auth/sessions" -> requireFields(content, name, "message");
            case "POST /auth/session/refresh" -> requireFields(content, name, "expiresAt", "lastAccessedAt");
            default -> objectFixture(content, name);
        }
    }

    private void assertOidcForm(JsonObject operation, JsonObject form) {
        for (var field : Set.of("code", "code_verifier", "state")) {
            assertTrue(form.getString(field) != null && !form.getString(field).isBlank(), field);
        }
        var cookies = operation.getJsonObject("requestCookies");
        assertNotNull(cookies, "OIDC exchange cookie setup");
        assertEquals(form.getString("state"), cookies.getString("aussie_oidc_state"));
        assertTrue(form.getString("code_verifier").matches("[A-Za-z0-9._~-]{43,128}"));
        var pkceSetup = operation.getJsonObject("pkceSetup");
        assertNotNull(pkceSetup, "OIDC PKCE setup");
        assertEquals("S256", pkceSetup.getString("codeChallengeMethod"));
        assertTrue(pkceSetup.getString("codeChallenge").matches("[A-Za-z0-9_-]{43}"));
    }

    private void assertValidTranslationConfig(TranslationConfigSchema config) {
        assertNotNull(config);
        assertTrue(config.version() >= 1);
        assertNotNull(config.sources());
        assertNotNull(config.mappings());
    }

    private void assertParseableJwt(String token) {
        assertNotNull(token);
        var parts = token.split("\\.", -1);
        assertEquals(3, parts.length);
        var claims = objectFixture(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), "JWT payload");
        assertEquals("contract-jti", claims.getString("jti"));
        try {
            new JwtConsumerBuilder()
                    .setSkipSignatureVerification()
                    .setSkipAllValidators()
                    .setDisableRequireSignature()
                    .setSkipAllDefaultValidators()
                    .build()
                    .processToClaims(token);
        } catch (InvalidJwtException e) {
            throw new AssertionError("JWT fixture is not parseable", e);
        }
    }

    private JsonObject requireFields(String content, String name, String... fields) {
        var object = objectFixture(content, name);
        for (var field : fields) {
            assertTrue(object.containsKey(field), name + " is missing field " + field);
        }
        return object;
    }

    private JsonArray arrayFixture(String content, String name) {
        try {
            return new JsonArray(content);
        } catch (RuntimeException e) {
            throw new AssertionError("fixture is not a JSON array: " + name, e);
        }
    }

    private JsonObject objectFixture(String content, String name) {
        try {
            return new JsonObject(content);
        } catch (RuntimeException e) {
            throw new AssertionError("fixture is not a JSON object: " + name, e);
        }
    }

    private <T> T decode(String content, Class<T> type, String name) {
        try {
            return Json.decodeValue(content, type);
        } catch (RuntimeException e) {
            throw new AssertionError("fixture does not decode as " + type.getSimpleName() + ": " + name, e);
        }
    }

    private String fixtureContent(String name) {
        try {
            var content = Files.readString(Path.of("..", "contract-fixtures", "control-plane", name));
            Json.decodeValue(content);
            return content;
        } catch (java.io.IOException | RuntimeException e) {
            throw new AssertionError("unable to read control-plane contract fixture " + name, e);
        }
    }

    private void assertFixtureIfPresent(String name) {
        if (name != null) {
            fixtureContent(name);
        }
    }
}
