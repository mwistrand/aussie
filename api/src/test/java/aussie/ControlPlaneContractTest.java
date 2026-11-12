package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.rest.ControlPlaneContract;

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

    private JsonObject fixture(String name) {
        try {
            return new JsonObject(Files.readString(Path.of("..", "contract-fixtures", "control-plane", name)));
        } catch (java.io.IOException e) {
            throw new AssertionError("unable to read control-plane contract fixture", e);
        }
    }
}
