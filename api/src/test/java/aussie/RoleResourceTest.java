package aussie;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.port.in.RoleManagement;

/**
 * Integration tests for Role management endpoints.
 *
 * <p>Uses default test profile with dangerous-noop enabled, which grants wildcard
 * permissions (effectively admin access).
 */
@QuarkusTest
@DisplayName("Role Resource Tests")
class RoleResourceTest {

    @Inject
    RoleManagement roleService;

    @AfterEach
    void tearDown() {
        // Clean up all roles after each test
        roleService.list().await().atMost(Duration.ofSeconds(5)).forEach(role -> roleService
                .delete(role.id())
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Nested
    @DisplayName("Role Creation")
    class RoleCreationTests {

        @Test
        @DisplayName("should create role with all fields")
        void shouldCreateRoleWithAllFields() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "platform-team",
                                "displayName": "Platform Team",
                                "description": "Team responsible for platform infrastructure",
                                "permissions": ["service.config.read", "service.config.create"]
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(201)
                    .body("id", equalTo("platform-team"))
                    .body("displayName", equalTo("Platform Team"))
                    .body("description", equalTo("Team responsible for platform infrastructure"))
                    .body("permissions", hasSize(2))
                    .body("permissions", containsInAnyOrder("service.config.read", "service.config.create"))
                    .body("createdAt", notNullValue())
                    .body("updatedAt", notNullValue());
        }

        @Test
        @DisplayName("should create role with minimal fields")
        void shouldCreateRoleWithMinimalFields() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "minimal-role"
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(201)
                    .body("id", equalTo("minimal-role"))
                    .body("displayName", equalTo("minimal-role"))
                    .body("permissions", hasSize(0));
        }

        @Test
        @DisplayName("should reject create without id")
        void shouldRejectCreateWithoutId() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "No ID Role",
                                "permissions": ["apikeys.read"]
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json")
                    .body("detail", equalTo("id is required"));
        }

        @Test
        @DisplayName("should reject create with blank id")
        void shouldRejectCreateWithBlankId() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "   ",
                                "displayName": "Blank ID Role"
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should reject duplicate role id")
        void shouldRejectDuplicateRoleId() {
            // Create first role
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "duplicate-test",
                                "displayName": "First Role"
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(201);

            // Try to create second role with same id
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "duplicate-test",
                                "displayName": "Second Role"
                            }
                            """)
                    .when()
                    .post("/admin/roles")
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json");
        }
    }

    @Nested
    @DisplayName("Role Listing")
    class RoleListingTests {

        @Test
        @DisplayName("should return empty list when no roles")
        void shouldReturnEmptyListWhenNoRoles() {
            given().when().get("/admin/roles").then().statusCode(200).body("$", hasSize(0));
        }

        @Test
        @DisplayName("should list all roles")
        void shouldListAllRoles() {
            // Create several roles
            createRole("role-1", "Role 1");
            createRole("role-2", "Role 2");
            createRole("role-3", "Role 3");

            given().when().get("/admin/roles").then().statusCode(200).body("$", hasSize(3));
        }
    }

    @Nested
    @DisplayName("Role Retrieval")
    class RoleRetrievalTests {

        @Test
        @DisplayName("should get role by id")
        void shouldGetRoleById() {
            createRole("get-test", "Get Test Role");

            given().when()
                    .get("/admin/roles/get-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("get-test"))
                    .body("displayName", equalTo("Get Test Role"));
        }

        @Test
        @DisplayName("should return 404 for non-existent role")
        void shouldReturn404ForNonExistentRole() {
            given().when()
                    .get("/admin/roles/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }
    }

    @Nested
    @DisplayName("Role Update")
    class RoleUpdateTests {

        @Test
        @DisplayName("should update role display name")
        void shouldUpdateRoleDisplayName() {
            createRole("update-test", "Original Name");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "Updated Name"
                            }
                            """)
                    .when()
                    .put("/admin/roles/update-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("update-test"))
                    .body("displayName", equalTo("Updated Name"));
        }

        @Test
        @DisplayName("should update role permissions")
        void shouldUpdateRolePermissions() {
            createRole("perm-update-test", "Permission Update Test");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "permissions": ["apikeys.read", "apikeys.write", "service.config.read"]
                            }
                            """)
                    .when()
                    .put("/admin/roles/perm-update-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("perm-update-test"))
                    .body("permissions", hasSize(3))
                    .body("permissions", containsInAnyOrder("apikeys.read", "apikeys.write", "service.config.read"));
        }

        @Test
        @DisplayName("should update multiple fields at once")
        void shouldUpdateMultipleFields() {
            createRole("multi-update", "Original");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "New Display Name",
                                "description": "New description",
                                "permissions": ["*"]
                            }
                            """)
                    .when()
                    .put("/admin/roles/multi-update")
                    .then()
                    .statusCode(200)
                    .body("displayName", equalTo("New Display Name"))
                    .body("description", equalTo("New description"))
                    .body("permissions", containsInAnyOrder("*"));
        }

        @Test
        @DisplayName("should return 404 when updating non-existent role")
        void shouldReturn404WhenUpdatingNonExistent() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "New Name"
                            }
                            """)
                    .when()
                    .put("/admin/roles/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }

        @Test
        @DisplayName("should reject update without request body")
        void shouldRejectUpdateWithoutBody() {
            createRole("no-body-test", "Test");

            given().contentType(ContentType.JSON)
                    .when()
                    .put("/admin/roles/no-body-test")
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("Role Deletion")
    class RoleDeletionTests {

        @Test
        @DisplayName("should delete existing role")
        void shouldDeleteExistingRole() {
            createRole("to-delete", "To Delete");

            given().when().delete("/admin/roles/to-delete").then().statusCode(204);

            // Verify it's gone
            given().when().get("/admin/roles/to-delete").then().statusCode(404);
        }

        @Test
        @DisplayName("should return 404 when deleting non-existent role")
        void shouldReturn404WhenDeletingNonExistent() {
            given().when()
                    .delete("/admin/roles/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }
    }

    /**
     * Helper method to create a role for testing.
     */
    private void createRole(String id, String displayName) {
        var requestBody = String.format(
                """
                {
                    "id": "%s",
                    "displayName": "%s"
                }
                """,
                id, displayName);

        given().contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/admin/roles")
                .then()
                .statusCode(201);
    }
}
