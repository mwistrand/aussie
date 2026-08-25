package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

import java.net.URI;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.E2EHarness;
import aussie.e2e.support.SuiteContext;

@DisplayName("Packaged deployment lifecycle")
final class DeploymentLifecycleE2ETest {

    @Test
    @DisplayName("restarts preserve readiness and routed traffic")
    void repeatedRestartsPreserveReadinessAndRouting() {
        final var harness = E2EHarness.start();
        final var context = SuiteContext.get();

        for (var cycle = 0; cycle < 3; cycle++) {
            harness.restartApi();
            Awaitility.await("gateway readiness after restart")
                    .atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptions()
                    .untilAsserted(
                            () -> given().baseUri(context.gatewayBaseUri().toString())
                                    .get("/q/health/ready")
                                    .then()
                                    .statusCode(200));

            for (var request = 0; request < 25; request++) {
                given().baseUri(context.gatewayBaseUri().toString())
                        .when()
                        .get("/{serviceId}/api/health", context.demoServiceId())
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("healthy"))
                        .body("service", equalTo("demo-service"));
            }
        }
    }

    @Test
    @DisplayName("a second instance serves traffic during a rolling restart")
    void rollingRestartKeepsReplicaServingTraffic() {
        final var harness = E2EHarness.start();
        final var context = SuiteContext.get();
        var primaryStopAttempted = false;

        try {
            harness.startReplica();
            awaitReady(harness.replicaBaseUri());
            assertRouted(harness.replicaBaseUri(), context);

            primaryStopAttempted = true;
            harness.stopPrimary();
            for (var request = 0; request < 25; request++) {
                assertRouted(harness.replicaBaseUri(), context);
            }
        } finally {
            try {
                if (primaryStopAttempted) {
                    harness.restartPrimary();
                }
            } finally {
                harness.stopReplica();
            }
        }
    }

    private static void awaitReady(URI baseUri) {
        Awaitility.await("gateway readiness")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .untilAsserted(() -> given().baseUri(baseUri.toString())
                        .get("/q/health/ready")
                        .then()
                        .statusCode(200));
    }

    private static void assertRouted(URI baseUri, SuiteContext context) {
        given().baseUri(baseUri.toString())
                .when()
                .get("/{serviceId}/api/health", context.demoServiceId())
                .then()
                .statusCode(200)
                .body("status", equalTo("healthy"))
                .body("service", equalTo("demo-service"));
    }
}
