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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

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

    private static final Logger AUDIT = Logger.getLogger("aussie.audit.admin");
    // ponytail: process-local replay only; move keys to shared storage when cross-instance replay is required.
    private static final Cache<IdempotencyCacheKey, IdempotentOperation> IDEMPOTENT_OPERATIONS = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(java.time.Duration.ofMinutes(10))
            .build();

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
                return idempotent(
                        identity, idempotencyKey, new RegistrationFingerprint(service, expectedVersion), operation);
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

    private Uni<Response> idempotent(
            SecurityIdentity identity,
            String idempotencyKey,
            RegistrationFingerprint fingerprint,
            Supplier<Uni<Response>> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        if (idempotencyKey.length() > 128) {
            throw GatewayProblem.badRequest("Idempotency-Key must be 128 characters or less");
        }

        var cacheKey = new IdempotencyCacheKey(
                identityAttribute(identity, "authenticationMethod"),
                identityAttribute(identity, "issuer"),
                principalId(identity),
                idempotencyKey);
        var existing = IDEMPOTENT_OPERATIONS.getIfPresent(cacheKey);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw GatewayProblem.conflict("Idempotency-Key was already used for a different request");
            }
            return existing.result();
        }

        var result = action.get().memoize().indefinitely();
        var prior = IDEMPOTENT_OPERATIONS.asMap().putIfAbsent(cacheKey, new IdempotentOperation(fingerprint, result));
        return prior == null
                ? result
                : prior.fingerprint().equals(fingerprint)
                        ? prior.result()
                        : Uni.createFrom()
                                .failure(GatewayProblem.conflict(
                                        "Idempotency-Key was already used for a different request"));
    }

    private void audit(SecurityIdentity identity, String action, String target, String outcome) {
        AUDIT.infof(
                "admin_mutation action=%s actor=%s target=%s outcome=%s",
                action, actor(identity), auditValue(target), outcome);
    }

    private String actor(SecurityIdentity identity) {
        var principalId = principalId(identity);
        var issuer = identityAttribute(identity, "issuer");
        return auditValue(issuer == null ? principalId : issuer + ":" + principalId);
    }

    private String principalId(SecurityIdentity identity) {
        var principalId = identityAttribute(identity, "principalId");
        if (principalId != null) {
            return principalId;
        }
        var principal = identity == null ? null : identity.getPrincipal();
        var name = principal == null ? "anonymous" : principal.getName();
        return name == null ? "unknown" : name;
    }

    private String identityAttribute(SecurityIdentity identity, String name) {
        Object value = identity == null ? null : identity.getAttribute(name);
        return value == null ? null : value.toString();
    }

    private String auditValue(String value) {
        return value.replaceAll("[^A-Za-z0-9_.:@-]", "_");
    }

    private record IdempotencyCacheKey(String authenticationMethod, String issuer, String principalId, String key) {}

    private record RegistrationFingerprint(ServiceRegistration service, Long expectedVersion) {}

    private record IdempotentOperation(RegistrationFingerprint fingerprint, Uni<Response> result) {}
}
