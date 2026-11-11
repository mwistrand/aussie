package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.rest.ControlPlaneContract;

@QuarkusTest
class ControlPlaneContractTest {

    @Test
    void exposesCapabilitiesAndContractVersion() {
        given().when()
                .get("/admin/capabilities")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .header(ControlPlaneContract.VERSION_HEADER, equalTo(ControlPlaneContract.VERSION))
                .body("contractVersion", equalTo(ControlPlaneContract.VERSION))
                .body("supportedContractVersions[0]", equalTo(ControlPlaneContract.VERSION))
                .body("openApi", equalTo(ControlPlaneContract.SPECIFICATION_PATH));
    }

    @Test
    void rejectsUnsupportedControlPlaneVersion() {
        given().header(ControlPlaneContract.VERSION_HEADER, "v99")
                .when()
                .get("/admin/capabilities")
                .then()
                .statusCode(406)
                .body("supportedVersion", equalTo(ControlPlaneContract.VERSION))
                .body("requestedVersion", equalTo("v99"));
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
}
