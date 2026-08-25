package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.dto.CreateRoleRequest;
import aussie.adapter.in.dto.UpdateRoleRequest;
import aussie.core.model.auth.Role;
import aussie.core.service.auth.RoleService;

@DisplayName("RoleResource")
@ExtendWith(MockitoExtension.class)
class RoleResourceUnitTest {

    @Mock
    private RoleService roleService;

    private RoleResource resource;

    @BeforeEach
    void setUp() {
        resource = new RoleResource(roleService);
    }

    private Role createRole(String id) {
        return Role.builder(id)
                .displayName(id + " display")
                .description("description for " + id)
                .permissions(Set.of("read", "write"))
                .build();
    }

    @Nested
    @DisplayName("createRole")
    class CreateRole {

        @Test
        @DisplayName("should return 201 on success")
        void shouldReturn201OnSuccess() {
            var role = createRole("platform-team");
            when(roleService.create(eq("platform-team"), eq("Platform Team"), eq("desc"), eq(Set.of("read")), isNull()))
                    .thenReturn(Uni.createFrom().item(role));

            var request = new CreateRoleRequest("platform-team", "Platform Team", "desc", Set.of("read"));
            var response = resource.createRole(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            assertEquals(role, response.getEntity());
        }

        @Test
        @DisplayName("should retain a requested team in direct construction")
        void shouldRetainRequestedTeamInDirectConstruction() {
            var role = Role.builder("team-role")
                    .teamId("team-a")
                    .permissions(Set.of("read"))
                    .build();
            when(roleService.create("team-role", null, null, Set.of("read"), "team-a"))
                    .thenReturn(Uni.createFrom().item(role));

            var request = new CreateRoleRequest("team-role", null, null, Set.of("read"), "team-a");
            var response = resource.createRole(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(role, response.getEntity());
        }
    }

    @Nested
    @DisplayName("listRoles")
    class ListRoles {

        @Test
        @DisplayName("should return list of roles")
        void shouldReturnListOfRoles() {
            var roles = List.of(createRole("role-1"), createRole("role-2"));
            when(roleService.list(20, 5)).thenReturn(Uni.createFrom().item(roles));

            final var result = resource.listRoles(20, 5).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("getRole")
    class GetRole {

        @Test
        @DisplayName("should return role when found")
        void shouldReturnRoleWhenFound() {
            var role = createRole("platform-team");
            when(roleService.get("platform-team")).thenReturn(Uni.createFrom().item(Optional.of(role)));

            var response = resource.getRole("platform-team").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertEquals(role, response.getEntity());
            assertEquals("\"1\"", response.getHeaderString("ETag"));
        }

        @Test
        @DisplayName("should throw HttpProblem when role not found")
        void shouldThrowWhenRoleNotFound() {
            when(roleService.get("unknown")).thenReturn(Uni.createFrom().item(Optional.empty()));

            var ex = assertThrows(
                    HttpProblem.class, () -> resource.getRole("unknown").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("updateRole")
    class UpdateRole {

        @Test
        @DisplayName("should throw HttpProblem when permissions and addPermissions both set")
        void shouldThrowWhenPermissionsAndAddPermissionsConflict() {
            var request = new UpdateRoleRequest("New Name", null, Set.of("read"), Set.of("write"), null);

            var ex = assertThrows(HttpProblem.class, () -> resource.updateRole("role-1", request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem when permissions and removePermissions both set")
        void shouldThrowWhenPermissionsAndRemovePermissionsConflict() {
            var request = new UpdateRoleRequest(null, null, Set.of("read"), null, Set.of("write"));

            var ex = assertThrows(HttpProblem.class, () -> resource.updateRole("role-1", request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should return updated role on success")
        void shouldReturnUpdatedRoleOnSuccess() {
            var updatedRole = createRole("role-1");
            when(roleService.update(eq("role-1"), eq("New Name"), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(Uni.createFrom().item(Optional.of(updatedRole)));

            var request = new UpdateRoleRequest("New Name", null, null, null, null);
            var response = resource.updateRole("role-1", request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertEquals(updatedRole, response.getEntity());
        }

        @Test
        @DisplayName("should throw HttpProblem when role not found on update")
        void shouldThrowWhenRoleNotFoundOnUpdate() {
            when(roleService.update(eq("unknown"), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var request = new UpdateRoleRequest(null, null, null, null, null);

            assertThrows(
                    HttpProblem.class,
                    () -> resource.updateRole("unknown", request).await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("deleteRole")
    class DeleteRole {

        @Test
        @DisplayName("should return 204 when deletion succeeds")
        void shouldReturn204WhenDeletionSucceeds() {
            when(roleService.delete("role-1")).thenReturn(Uni.createFrom().item(true));

            var response = resource.deleteRole("role-1").await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when role not found on delete")
        void shouldThrowWhenRoleNotFoundOnDelete() {
            when(roleService.delete("unknown")).thenReturn(Uni.createFrom().item(false));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.deleteRole("unknown").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }
}
