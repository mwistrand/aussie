package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.UUID;

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
