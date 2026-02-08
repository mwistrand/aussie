package aussie.adapter.in.rest;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.dto.CreateRoleRequest;
import aussie.adapter.in.dto.UpdateRoleRequest;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.Role;
import aussie.core.service.auth.RoleService;

/**
 * REST resource for RBAC role management.
 *
 * <p>Provides endpoints for creating, listing, updating, and deleting roles
 * that map to permission sets. Roles are used to expand token role claims
 * into effective permissions at validation time.
 *
 * <p>Authorization is enforced via {@code @PermissionsAllowed} annotations:
 * <ul>
 * <li>POST (create) requires {@code auth.roles.create} or {@code admin} permission</li>
 * <li>GET (list/read) requires {@code auth.roles.read} or {@code admin} permission</li>
 * <li>PUT (update) requires {@code auth.roles.update} or {@code admin} permission</li>
 * <li>DELETE requires {@code auth.roles.delete} or {@code admin} permission</li>
 * </ul>
 */
@Path("/admin/roles")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RoleResource {

    private final RoleService roleService;

    @Inject
    public RoleResource(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * Create a new role.
     *
     * @param request the role creation request
     * @return 201 Created with the new role, or 400 if validation fails
     */
    @POST
    @PermissionsAllowed({Permission.AUTH_ROLES_CREATE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> createRole(@Valid CreateRoleRequest request) {
        return roleService
                .create(request.id(), request.displayName(), request.description(), request.permissions())
                .map(role ->
                        Response.status(Response.Status.CREATED).entity(role).build());
    }

    /**
     * List all roles.
     *
     * @return list of all roles
     */
    @GET
    @PermissionsAllowed({Permission.AUTH_ROLES_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<List<Role>> listRoles() {
        return roleService.list();
    }

    /**
     * Get a specific role by ID.
     *
     * @param roleId the role ID
     * @return the role or 404 if not found
     */
    @GET
    @Path("/{roleId}")
    @PermissionsAllowed({Permission.AUTH_ROLES_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getRole(@PathParam("roleId") String roleId) {
        return roleService.get(roleId).map(opt -> opt.map(
                        role -> Response.ok(role).build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("Role", roleId)));
    }

    /**
     * Update a role.
     *
     * <p>Only non-null fields in the request are updated.
     *
     * <p>Permission updates support three modes:
     * <ul>
     *   <li>{@code permissions}: Replace all permissions (mutually exclusive with add/remove)</li>
     *   <li>{@code addPermissions}: Add permissions to existing set</li>
     *   <li>{@code removePermissions}: Remove permissions from existing set</li>
     * </ul>
     *
     * @param roleId the role ID to update
     * @param request the update request
     * @return the updated role or 404 if not found
     */
    @PUT
    @Path("/{roleId}")
    @PermissionsAllowed({Permission.AUTH_ROLES_UPDATE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> updateRole(
            @PathParam("roleId") String roleId,
            @NotNull(message = "request body is required") @Valid UpdateRoleRequest request) {
        // Validate mutual exclusivity
        if (request.permissions() != null
                && (request.addPermissions() != null || request.removePermissions() != null)) {
            throw GatewayProblem.badRequest("permissions cannot be used with addPermissions or removePermissions");
        }

        return roleService
                .update(
                        roleId,
                        request.displayName(),
                        request.description(),
                        request.permissions(),
                        request.addPermissions(),
                        request.removePermissions())
                .map(opt -> opt.map(role -> Response.ok(role).build())
                        .orElseThrow(() -> GatewayProblem.resourceNotFound("Role", roleId)));
    }

    /**
     * Delete a role.
     *
     * @param roleId the role ID to delete
     * @return 204 No Content if deleted, or 404 if not found
     */
    @DELETE
    @Path("/{roleId}")
    @PermissionsAllowed({Permission.AUTH_ROLES_DELETE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> deleteRole(@PathParam("roleId") String roleId) {
        return roleService.delete(roleId).map(deleted -> {
            if (deleted) {
                return Response.noContent().build();
            } else {
                throw GatewayProblem.resourceNotFound("Role", roleId);
            }
        });
    }
}
