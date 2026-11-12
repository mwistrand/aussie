package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.port.in.RoleManagement;

/**
 * Integration smoke tests for Role management endpoints.
 *
 * <p>Validation, error mapping, and per-method branches live in
 * {@code RoleResourceUnitTest}. This class verifies the real CRUD round-trip
 * over JAX-RS + the in-memory repository.
 */
@QuarkusTest
@DisplayName("Role Resource Tests")
class RoleResourceTest {

    @Inject
    RoleManagement roleService;

    @AfterEach
    void tearDown() {
        roleService.list().await().atMost(Duration.ofSeconds(5)).forEach(role -> roleService
                .delete(role.id())
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("should create, update, list and delete a role end-to-end")
    void shouldRoundTripRoleLifecycle() {
        var created = given().contentType(ContentType.JSON)
                .body("""
                        {
                            "id": "platform-team",
                            "displayName": "Platform Team",
                            "description": "Platform infrastructure team",
                            "permissions": ["service.config.read", "service.config.create"]
                        }
                        """)
                .when()
                .post("/admin/roles")
                .then()
                .statusCode(201)
                .body("id", equalTo("platform-team"))
                .body("permissions", containsInAnyOrder("service.config.read", "service.config.create"))
                .extract()
                .response();

        var fixture = fixture("role.json");
        var actual = new JsonObject(created.asString());
        for (var field : new String[] {"id", "displayName", "description", "version"}) {
            assertEquals(fixture.getValue(field), actual.getValue(field));
        }
        assertEquals(
                Set.copyOf(fixture.getJsonArray("permissions").getList()),
                Set.copyOf(actual.getJsonArray("permissions").getList()));

        given().contentType(ContentType.JSON)
                .body("""
                        {
                            "displayName": "Updated Platform Team",
                            "permissions": ["*"]
                        }
                        """)
                .when()
                .put("/admin/roles/platform-team")
                .then()
                .statusCode(200)
                .body("displayName", equalTo("Updated Platform Team"))
                .body("permissions", containsInAnyOrder("*"));

        given().when().get("/admin/roles").then().statusCode(200).body("$", hasSize(1));

        given().when().delete("/admin/roles/platform-team").then().statusCode(204);
        given().when().get("/admin/roles/platform-team").then().statusCode(404);
    }

    @Test
    @DisplayName("should map validation failures to problem+json")
    void shouldMapValidationFailuresToProblemJson() {
        given().contentType(ContentType.JSON)
                .body("""
                        {
                            "displayName": "No ID Role"
                        }
                        """)
                .when()
                .post("/admin/roles")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("urn:aussie:problem:bad_request"))
                .body("code", equalTo("bad_request"))
                .body("violations[0].message", equalTo("id is required"));
    }

    private JsonObject fixture(String name) {
        try {
            return new JsonObject(Files.readString(Path.of("..", "contract-fixtures", "control-plane", name)));
        } catch (java.io.IOException e) {
            throw new AssertionError("unable to read control-plane contract fixture", e);
        }
    }
}
