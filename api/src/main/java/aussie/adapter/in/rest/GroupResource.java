package aussie.adapter.in.rest;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

import io.smallrye.mutiny.Uni;

import aussie.adapter.in.dto.CreateGroupRequest;
import aussie.adapter.in.dto.UpdateGroupRequest;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.Group;
import aussie.core.model.auth.Permission;
import aussie.core.port.in.GroupManagement;

/**
 * REST resource for RBAC group management.
 *
 * <p>Provides endpoints for creating, listing, updating, and deleting groups
 * that map to permission sets. Groups are used to expand token group claims
 * into effective permissions at validation time.
 *
 * <p>Authorization is enforced via {@code @RolesAllowed} annotations:
 * <ul>
 * <li>POST (create) requires {@code auth.groups.create} or {@code admin} role</li>
 * <li>GET (list/read) requires {@code auth.groups.read} or {@code admin} role</li>
 * <li>PUT (update) requires {@code auth.groups.update} or {@code admin} role</li>
 * <li>DELETE requires {@code auth.groups.delete} or {@code admin} role</li>
 * </ul>
 */
@Path("/admin/groups")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GroupResource {

    private final GroupManagement groupService;

    @Inject
    public GroupResource(GroupManagement groupService) {
        this.groupService = groupService;
    }

    /**
     * Create a new group.
     *
     * @param request the group creation request
     * @return 201 Created with the new group, or 400 if validation fails
     */
    @POST
    @RolesAllowed({Permission.AUTH_GROUPS_CREATE, Permission.ADMIN})
    public Uni<Response> createGroup(CreateGroupRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw GatewayProblem.badRequest("id is required");
        }

        return groupService
                .create(request.id(), request.displayName(), request.description(), request.permissions())
                .map(group ->
                        Response.status(Response.Status.CREATED).entity(group).build())
                .onFailure(IllegalArgumentException.class)
                .transform(e -> GatewayProblem.validationError(e.getMessage()));
    }

    /**
     * List all groups.
     *
     * @return list of all groups
     */
    @GET
    @RolesAllowed({Permission.AUTH_GROUPS_READ, Permission.ADMIN})
    public Uni<List<Group>> listGroups() {
        return groupService.list();
    }

    /**
     * Get a specific group by ID.
     *
     * @param groupId the group ID
     * @return the group or 404 if not found
     */
    @GET
    @Path("/{groupId}")
    @RolesAllowed({Permission.AUTH_GROUPS_READ, Permission.ADMIN})
    public Uni<Response> getGroup(@PathParam("groupId") String groupId) {
        return groupService.get(groupId).map(opt -> opt.map(
                        group -> Response.ok(group).build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("Group", groupId)));
    }

    /**
     * Update a group.
     *
     * <p>Only non-null fields in the request are updated.
     *
     * @param groupId the group ID to update
     * @param request the update request
     * @return the updated group or 404 if not found
     */
    @PUT
    @Path("/{groupId}")
    @RolesAllowed({Permission.AUTH_GROUPS_UPDATE, Permission.ADMIN})
    public Uni<Response> updateGroup(@PathParam("groupId") String groupId, UpdateGroupRequest request) {
        if (request == null) {
            throw GatewayProblem.badRequest("Request body is required");
        }

        return groupService
                .update(groupId, request.displayName(), request.description(), request.permissions())
                .map(opt -> opt.map(group -> Response.ok(group).build())
                        .orElseThrow(() -> GatewayProblem.resourceNotFound("Group", groupId)));
    }

    /**
     * Delete a group.
     *
     * @param groupId the group ID to delete
     * @return 204 No Content if deleted, or 404 if not found
     */
    @DELETE
    @Path("/{groupId}")
    @RolesAllowed({Permission.AUTH_GROUPS_DELETE, Permission.ADMIN})
    public Uni<Response> deleteGroup(@PathParam("groupId") String groupId) {
        return groupService.delete(groupId).map(deleted -> {
            if (deleted) {
                return Response.noContent().build();
            } else {
                throw GatewayProblem.resourceNotFound("Group", groupId);
            }
        });
    }
}
