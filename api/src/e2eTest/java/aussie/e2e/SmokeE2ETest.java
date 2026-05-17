package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.SuiteContext;

/**
 * Round-trip smoke test that proves the harness is wired correctly:
 * gateway is up, demo is up, the service registration step succeeded, and
 * a request through the gateway reaches the demo and comes back unchanged.
 */
@DisplayName("E2E smoke")
final class SmokeE2ETest {

    @Test
    @DisplayName("GET /demo-service/api/health returns the demo's health body via the gateway")
    void demoHealthRoundTripsThroughGateway() {
        var ctx = SuiteContext.get();
        given().baseUri(ctx.gatewayBaseUri().toString())
                .when()
                .get("/{serviceId}/api/health", ctx.demoServiceId())
                .then()
                .statusCode(200)
                .body("status", equalTo("healthy"))
                .body("service", equalTo("demo-service"));
    }
}
