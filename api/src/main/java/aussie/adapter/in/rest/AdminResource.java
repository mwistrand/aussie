package aussie.adapter.in.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.auth.Permission;
import aussie.core.model.routing.RoutingSnapshotStatus;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

/**
 * REST resource for service administration.
 *
 * <p>
 * This adapter handles HTTP-specific concerns (request/response mapping, status
 * codes) and delegates all business logic, validation, and authorization to
 * {@link ServiceRegistry}.
 */
@Path("/admin/services")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final ServiceRegistry serviceRegistry;
    private final CurrentIdentityAssociation identityAssociation;
    private final GatewaySecurityConfig securityConfig;

    @Inject
    public AdminResource(
            ServiceRegistry serviceRegistry,
            CurrentIdentityAssociation identityAssociation,
            GatewaySecurityConfig securityConfig) {
        this.serviceRegistry = serviceRegistry;
        this.identityAssociation = identityAssociation;
        this.securityConfig = securityConfig;
    }

    public Uni<Response> registerService(@Valid ServiceRegistrationRequest request) {
        return registerService(request, null, null);
    }

    @POST
    @PermissionsAllowed({
        Permission.SERVICE_CONFIG_CREATE_VALUE,
        Permission.SERVICE_CONFIG_UPDATE_VALUE,
        Permission.ADMIN_VALUE
    })
    public Uni<Response> registerService(
            @Valid ServiceRegistrationRequest request,
            @HeaderParam("If-Match") String ifMatch,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        try {
            var service = request.toModel(securityConfig.allowPrivateUpstreams());
            var expectedVersion = VersionPreconditions.parseIfMatch(ifMatch);

            return identityAssociation.getDeferredIdentity().flatMap(identity -> {
                var claims = extractClaims(identity);
                Supplier<Uni<Response>> operation = () -> {
                    var registration = expectedVersion == null
                            ? serviceRegistry.register(service, claims)
                            : serviceRegistry.register(service, claims, expectedVersion);
                    return registration.map(result -> switch (result) {
                        case RegistrationResult.Success s -> {
                            audit(identity, "service.register", service.serviceId(), "success");
                            yield Response.status(Response.Status.CREATED)
                                    .entity(ServiceRegistrationResponse.fromModel(s.registration()))
                                    .header(
                                            "ETag",
                                            VersionPreconditions.etag(
                                                    s.registration().version()))
                                    .build();
                        }
                        case RegistrationResult.Failure f -> {
                            audit(identity, "service.register", service.serviceId(), "rejected_" + f.statusCode());
                            throw toGatewayProblem(f);
                        }
                    });
                };
                return AdminMutationSupport.idempotent(
                        identity,
                        "service.register",
                        idempotencyKey,
                        new RegistrationFingerprint(service, expectedVersion),
                        operation);
            });
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.validationError("Invalid service registration");
        }
    }

    public Uni<Response> unregisterService(String serviceId) {
        return unregisterService(serviceId, null);
    }

    @DELETE
    @Path("/{serviceId}")
    @PermissionsAllowed({Permission.SERVICE_CONFIG_DELETE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> unregisterService(
            @PathParam("serviceId") String serviceId, @HeaderParam("If-Match") String ifMatch) {
        final Long expectedVersion = VersionPreconditions.parseIfMatch(ifMatch);
        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var claims = extractClaims(identity);

            var deletion = expectedVersion == null
                    ? serviceRegistry.unregisterAuthorized(serviceId, claims)
                    : serviceRegistry.unregisterAuthorized(serviceId, claims, expectedVersion);
            return deletion.map(result -> switch (result) {
                case RegistrationResult.Success s -> {
                    audit(identity, "service.delete", serviceId, "success");
                    yield Response.noContent().build();
                }
                case RegistrationResult.Failure f -> {
                    audit(identity, "service.delete", serviceId, "rejected_" + f.statusCode());
                    throw toGatewayProblem(f);
                }
            });
        });
    }

    @GET
    @PermissionsAllowed({Permission.SERVICE_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<List<ServiceRegistrationResponse>> listServices(
            @QueryParam("limit") @DefaultValue("50") int limit, @QueryParam("offset") @DefaultValue("0") int offset) {
        AdminPagination.validate(limit, offset);
        return identityAssociation
                .getDeferredIdentity()
                .map(this::extractClaims)
                .flatMap(claims -> serviceRegistry
                        .getServicesAuthorized(limit, offset, claims)
                        .map(services -> services.stream()
                                .map(ServiceRegistrationResponse::fromModel)
                                .toList()));
    }

    @GET
    @Path("/routing-status")
    @PermissionsAllowed({Permission.SERVICE_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<RoutingSnapshotStatus> routingStatus() {
        return serviceRegistry.routingStatus();
    }

    @GET
    @Path("/{serviceId}")
    @PermissionsAllowed({Permission.SERVICE_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getService(@PathParam("serviceId") String serviceId) {
        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var claims = extractClaims(identity);

            return serviceRegistry.getServiceAuthorized(serviceId, claims).map(result -> switch (result) {
                case RegistrationResult.Success s -> Response.ok(
                                ServiceRegistrationResponse.fromModel(s.registration()))
                        .header(
                                "ETag",
                                VersionPreconditions.etag(s.registration().version()))
                        .build();
                case RegistrationResult.Failure f -> throw toGatewayProblem(f);
            });
        });
    }

    /**
     * Extract effective permissions from the identity for service-level authorization.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractClaims(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return Set.of();
        }

        var result = new HashSet<String>();

        var permissions = identity.getAttribute("permissions");
        if (permissions instanceof Set) {
            result.addAll((Set<String>) permissions);
        }

        return result;
    }

    /**
     * Convert a RegistrationResult.Failure to the appropriate HttpProblem
     * exception.
     */
    private HttpProblem toGatewayProblem(RegistrationResult.Failure failure) {
        return switch (failure.statusCode()) {
            case 403 -> GatewayProblem.forbidden(failure.reason());
            case 404 -> GatewayProblem.resourceNotFound("Service", failure.reason());
            case 409 -> GatewayProblem.conflict(failure.reason());
            case 412 -> GatewayProblem.preconditionFailed(failure.reason());
            default -> GatewayProblem.badRequest(failure.reason());
        };
    }

    private void audit(SecurityIdentity identity, String action, String target, String outcome) {
        AdminMutationSupport.audit(identity, action, target, outcome);
    }

    private record RegistrationFingerprint(ServiceRegistration service, Long expectedVersion) {}
}
