package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.restassured.response.Response;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.E2EHarness;
import aussie.e2e.support.SuiteContext;

@DisplayName("Cross-instance administrative mutations")
final class AdminMutationE2ETest {

    @Test
    @DisplayName("replays an idempotent service registration on a replica")
    void replaysRegistrationAcrossInstances() {
        var harness = E2EHarness.start();
        var context = SuiteContext.get();
        var serviceId = "idempotency-e2e-" + UUID.randomUUID().toString().replace('-', 'a');
        var idempotencyKey = UUID.randomUUID().toString();
        var body = "{\"version\":1,\"serviceId\":\"" + serviceId
                + "\",\"baseUrl\":\"http://demo:3000\",\"routePrefix\":\"/" + serviceId
                + "\",\"defaultVisibility\":\"PRIVATE\",\"defaultAuthRequired\":true}";

        Response first = register(context.gatewayBaseUri().toString(), context.bootstrapKey(), idempotencyKey, body);
        assertEquals(201, first.statusCode(), first.asString());

        try {
            harness.startReplica();
            awaitReady(harness.replicaBaseUri().toString());
            Response replay =
                    register(harness.replicaBaseUri().toString(), context.bootstrapKey(), idempotencyKey, body);

            assertEquals(201, replay.statusCode(), replay.asString());
            assertEquals(first.getHeader("ETag"), replay.getHeader("ETag"));
            assertEquals(first.asString(), replay.asString());
        } finally {
            harness.stopReplica();
        }
    }

    @Test
    @DisplayName("enforces shared optimistic concurrency for roles and API keys")
    void enforcesSharedOptimisticConcurrency() {
        var harness = E2EHarness.start();
        var context = SuiteContext.get();
        var roleId = "cas-e2e-" + UUID.randomUUID().toString().replace('-', 'a');
        var keyName = "cas-key-e2e-" + UUID.randomUUID();

        try {
            harness.startReplica();
            awaitReady(harness.replicaBaseUri().toString());

            var role = createRole(context.gatewayBaseUri().toString(), context.bootstrapKey(), roleId);
            assertEquals(201, role.statusCode(), role.asString());
            var roleEtag = role.getHeader("ETag");
            var roleUpdates = List.of(
                    CompletableFuture.supplyAsync(() -> updateRole(
                            context.gatewayBaseUri().toString(), context.bootstrapKey(), roleId, roleEtag, "primary")),
                    CompletableFuture.supplyAsync(() -> updateRole(
                            harness.replicaBaseUri().toString(), context.bootstrapKey(), roleId, roleEtag, "replica")));

            var roleStatuses = roleUpdates.stream()
                    .map(CompletableFuture::join)
                    .map(Response::statusCode)
                    .toList();
            assertEquals(
                    1, roleStatuses.stream().filter(status -> status == 200).count());
            assertEquals(
                    1, roleStatuses.stream().filter(status -> status == 412).count());

            var key = createKey(context.gatewayBaseUri().toString(), context.bootstrapKey(), keyName);
            assertEquals(201, key.statusCode(), key.asString());
            String keyId = key.path("keyId");
            String plaintextKey = key.path("key");
            var keyEtag = key.getHeader("ETag");
            var revocations = List.of(
                    CompletableFuture.supplyAsync(() ->
                            revokeKey(context.gatewayBaseUri().toString(), context.bootstrapKey(), keyId, keyEtag)),
                    CompletableFuture.supplyAsync(() ->
                            revokeKey(harness.replicaBaseUri().toString(), context.bootstrapKey(), keyId, keyEtag)));

            var revokeStatuses = revocations.stream()
                    .map(CompletableFuture::join)
                    .map(Response::statusCode)
                    .toList();
            assertEquals(
                    1, revokeStatuses.stream().filter(status -> status == 204).count());
            assertEquals(
                    1, revokeStatuses.stream().filter(status -> status == 412).count());

            Awaitility.await("durable admin audit records")
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        var stream = harness.adminAuditStream();
                        assertTrue(stream.contains("role.update"));
                        assertTrue(stream.contains(roleId));
                        assertTrue(stream.contains("api-key.revoke"));
                        assertTrue(stream.contains(keyId));
                        assertFalse(stream.contains(plaintextKey));
                    });
        } finally {
            harness.stopReplica();
        }
    }

    private static Response createRole(String baseUri, String key, String roleId) {
        return given().baseUri(baseUri)
                .contentType("application/json")
                .header("Authorization", "Bearer " + key)
                .body("{\"id\":\"" + roleId + "\",\"permissions\":[\"admin\"]}")
                .post("/admin/roles")
                .andReturn();
    }

    private static Response updateRole(String baseUri, String key, String roleId, String etag, String displayName) {
        return given().baseUri(baseUri)
                .contentType("application/json")
                .header("Authorization", "Bearer " + key)
                .header("If-Match", etag)
                .body("{\"displayName\":\"" + displayName + "\"}")
                .put("/admin/roles/" + roleId)
                .andReturn();
    }

    private static Response createKey(String baseUri, String key, String name) {
        return given().baseUri(baseUri)
                .contentType("application/json")
                .header("Authorization", "Bearer " + key)
                .body("{\"name\":\"" + name + "\",\"permissions\":[\"admin\"]}")
                .post("/admin/api-keys")
                .andReturn();
    }

    private static Response revokeKey(String baseUri, String key, String keyId, String etag) {
        return given().baseUri(baseUri)
                .header("Authorization", "Bearer " + key)
                .header("If-Match", etag)
                .delete("/admin/api-keys/" + keyId)
                .andReturn();
    }

    private static Response register(String baseUri, String key, String idempotencyKey, String body) {
        return given().baseUri(baseUri)
                .contentType("application/json")
                .header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .post("/admin/services")
                .andReturn();
    }

    private static void awaitReady(String baseUri) {
        Awaitility.await("gateway replica readiness")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .untilAsserted(() ->
                        given().baseUri(baseUri).get("/q/health/ready").then().statusCode(200));
    }
}
