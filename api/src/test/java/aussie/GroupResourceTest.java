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

import aussie.core.port.in.GroupManagement;

/**
 * Integration tests for Group management endpoints.
 *
 * <p>Uses default test profile with dangerous-noop enabled, which grants wildcard
 * permissions (effectively admin access).
 */
@QuarkusTest
@DisplayName("Group Resource Tests")
class GroupResourceTest {

    @Inject
    GroupManagement groupService;

    @AfterEach
    void tearDown() {
        // Clean up all groups after each test
        groupService.list().await().atMost(Duration.ofSeconds(5)).forEach(group -> groupService
                .delete(group.id())
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Nested
    @DisplayName("Group Creation")
    class GroupCreationTests {

        @Test
        @DisplayName("should create group with all fields")
        void shouldCreateGroupWithAllFields() {
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
                    .post("/admin/groups")
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
        @DisplayName("should create group with minimal fields")
        void shouldCreateGroupWithMinimalFields() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "minimal-group"
                            }
                            """)
                    .when()
                    .post("/admin/groups")
                    .then()
                    .statusCode(201)
                    .body("id", equalTo("minimal-group"))
                    .body("displayName", equalTo("minimal-group"))
                    .body("permissions", hasSize(0));
        }

        @Test
        @DisplayName("should reject create without id")
        void shouldRejectCreateWithoutId() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "No ID Group",
                                "permissions": ["apikeys.read"]
                            }
                            """)
                    .when()
                    .post("/admin/groups")
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
                                "displayName": "Blank ID Group"
                            }
                            """)
                    .when()
                    .post("/admin/groups")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("should reject duplicate group id")
        void shouldRejectDuplicateGroupId() {
            // Create first group
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "duplicate-test",
                                "displayName": "First Group"
                            }
                            """)
                    .when()
                    .post("/admin/groups")
                    .then()
                    .statusCode(201);

            // Try to create second group with same id
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "id": "duplicate-test",
                                "displayName": "Second Group"
                            }
                            """)
                    .when()
                    .post("/admin/groups")
                    .then()
                    .statusCode(400)
                    .contentType("application/problem+json");
        }
    }

    @Nested
    @DisplayName("Group Listing")
    class GroupListingTests {

        @Test
        @DisplayName("should return empty list when no groups")
        void shouldReturnEmptyListWhenNoGroups() {
            given().when().get("/admin/groups").then().statusCode(200).body("$", hasSize(0));
        }

        @Test
        @DisplayName("should list all groups")
        void shouldListAllGroups() {
            // Create several groups
            createGroup("group-1", "Group 1");
            createGroup("group-2", "Group 2");
            createGroup("group-3", "Group 3");

            given().when().get("/admin/groups").then().statusCode(200).body("$", hasSize(3));
        }
    }

    @Nested
    @DisplayName("Group Retrieval")
    class GroupRetrievalTests {

        @Test
        @DisplayName("should get group by id")
        void shouldGetGroupById() {
            createGroup("get-test", "Get Test Group");

            given().when()
                    .get("/admin/groups/get-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("get-test"))
                    .body("displayName", equalTo("Get Test Group"));
        }

        @Test
        @DisplayName("should return 404 for non-existent group")
        void shouldReturn404ForNonExistentGroup() {
            given().when()
                    .get("/admin/groups/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }
    }

    @Nested
    @DisplayName("Group Update")
    class GroupUpdateTests {

        @Test
        @DisplayName("should update group display name")
        void shouldUpdateGroupDisplayName() {
            createGroup("update-test", "Original Name");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "Updated Name"
                            }
                            """)
                    .when()
                    .put("/admin/groups/update-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("update-test"))
                    .body("displayName", equalTo("Updated Name"));
        }

        @Test
        @DisplayName("should update group permissions")
        void shouldUpdateGroupPermissions() {
            createGroup("perm-update-test", "Permission Update Test");

            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "permissions": ["apikeys.read", "apikeys.write", "service.config.read"]
                            }
                            """)
                    .when()
                    .put("/admin/groups/perm-update-test")
                    .then()
                    .statusCode(200)
                    .body("id", equalTo("perm-update-test"))
                    .body("permissions", hasSize(3))
                    .body("permissions", containsInAnyOrder("apikeys.read", "apikeys.write", "service.config.read"));
        }

        @Test
        @DisplayName("should update multiple fields at once")
        void shouldUpdateMultipleFields() {
            createGroup("multi-update", "Original");

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
                    .put("/admin/groups/multi-update")
                    .then()
                    .statusCode(200)
                    .body("displayName", equalTo("New Display Name"))
                    .body("description", equalTo("New description"))
                    .body("permissions", containsInAnyOrder("*"));
        }

        @Test
        @DisplayName("should return 404 when updating non-existent group")
        void shouldReturn404WhenUpdatingNonExistent() {
            given().contentType(ContentType.JSON)
                    .body(
                            """
                            {
                                "displayName": "New Name"
                            }
                            """)
                    .when()
                    .put("/admin/groups/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }

        @Test
        @DisplayName("should reject update without request body")
        void shouldRejectUpdateWithoutBody() {
            createGroup("no-body-test", "Test");

            given().contentType(ContentType.JSON)
                    .when()
                    .put("/admin/groups/no-body-test")
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("Group Deletion")
    class GroupDeletionTests {

        @Test
        @DisplayName("should delete existing group")
        void shouldDeleteExistingGroup() {
            createGroup("to-delete", "To Delete");

            given().when().delete("/admin/groups/to-delete").then().statusCode(204);

            // Verify it's gone
            given().when().get("/admin/groups/to-delete").then().statusCode(404);
        }

        @Test
        @DisplayName("should return 404 when deleting non-existent group")
        void shouldReturn404WhenDeletingNonExistent() {
            given().when()
                    .delete("/admin/groups/non-existent")
                    .then()
                    .statusCode(404)
                    .contentType("application/problem+json");
        }
    }

    /**
     * Helper method to create a group for testing.
     */
    private void createGroup(String id, String displayName) {
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
                .post("/admin/groups")
                .then()
                .statusCode(201);
    }
}
