package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

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
}
