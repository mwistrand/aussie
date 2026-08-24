package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class GatewayReadinessIntegrationTest {

    @Test
    void reportsReadyOnlyAfterStartupObserversComplete() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.name", hasItem("gateway-startup"))
                .body("checks.find { it.name == 'gateway-startup' }.status", equalTo("UP"))
                .body("checks.find { it.name == 'gateway-startup' }.data.phase", equalTo("READY"))
                .body("checks.name", hasItem("required-dependencies"))
                .body("checks.find { it.name == 'required-dependencies' }.status", equalTo("UP"));
    }
}
