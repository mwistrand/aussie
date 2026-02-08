package aussie.adapter.in.rest;

import java.security.Principal;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.adapter.in.dto.TranslationConfigUploadDto;
import aussie.adapter.in.dto.TranslationConfigValidationDto;
import aussie.adapter.in.dto.TranslationConfigVersionDto;
import aussie.adapter.in.dto.TranslationConfigVersionSummaryDto;
import aussie.adapter.in.dto.TranslationStatusDto;
import aussie.adapter.in.dto.TranslationTestRequestDto;
import aussie.adapter.in.dto.TranslationTestResultDto;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.service.auth.TokenTranslationService;
import aussie.core.service.auth.TranslationConfigService;
import aussie.core.service.auth.TranslationConfigService.ConfigValidationException;

/**
 * REST resource for managing token translation configurations.
 *
 * <p>Provides endpoints for uploading, listing, and managing translation
 * configuration versions, as well as testing configurations with sample claims.
 *
 * <p>Authorization is enforced via {@code @PermissionsAllowed} annotations:
 * <ul>
 * <li>Read operations require {@code translation.config.read} or {@code admin}</li>
 * <li>Write operations require {@code translation.config.write} or {@code admin}</li>
 * </ul>
 */
@Path("/admin/translation-config")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TranslationConfigResource {

    private static final Logger LOG = Logger.getLogger(TranslationConfigResource.class);

    private final TranslationConfigService configService;
    private final TokenTranslationService translationService;

    @Inject
    public TranslationConfigResource(
            TranslationConfigService configService, TokenTranslationService translationService) {
        this.configService = configService;
        this.translationService = translationService;
    }

    /**
     * Upload a new translation configuration.
     *
     * @param request the upload request containing the configuration
     * @param ctx security context for determining the uploader
     * @return 201 Created with the new version
     */
    @POST
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> uploadConfig(@Valid TranslationConfigUploadDto request, @Context SecurityContext ctx) {
        final var createdBy = getUserId(ctx);

        return configService
                .upload(request.config(), createdBy, request.comment(), request.activate())
                .invoke(version -> LOG.infof(
                        "Translation config uploaded: versionId=%s, version=%d, activate=%b, actor=%s",
                        version.id(), version.version(), request.activate(), createdBy))
                .map(version -> Response.status(Response.Status.CREATED)
                        .entity(TranslationConfigVersionDto.fromModel(version))
                        .build())
                .onFailure(ConfigValidationException.class)
                .transform(e -> {
                    final var ex = (ConfigValidationException) e;
                    throw GatewayProblem.badRequest("Validation failed: " + String.join(", ", ex.getErrors()));
                });
    }

    /**
     * Validate a translation configuration without storing it.
     *
     * @param config the configuration to validate
     * @return validation result
     */
    @POST
    @Path("/validate")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<TranslationConfigValidationDto> validateConfig(TranslationConfigSchema config) {
        if (config == null) {
            return Uni.createFrom().item(TranslationConfigValidationDto.failure(List.of("Configuration is required")));
        }

        return configService.validate(config).map(errors -> {
            if (errors.isEmpty()) {
                return TranslationConfigValidationDto.success();
            }
            return TranslationConfigValidationDto.failure(errors);
        });
    }

    /**
     * Test translation with sample claims.
     *
     * <p>If the request includes a configuration, that config is used for testing.
     * Otherwise, the currently active configuration is used.
     *
     * @param request the test request
     * @return translated claims result
     */
    @POST
    @Path("/test")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<TranslationTestResultDto> testTranslation(@Valid TranslationTestRequestDto request) {
        final var issuer = request.issuer() != null ? request.issuer() : "test-issuer";
        final var subject = request.subject() != null ? request.subject() : "test-subject";

        if (request.config() != null) {
            return configService
                    .testTranslation(request.config(), issuer, subject, request.claims())
                    .map(TranslationTestResultDto::fromModel)
                    .onFailure(ConfigValidationException.class)
                    .transform(e -> {
                        final var ex = (ConfigValidationException) e;
                        throw GatewayProblem.badRequest("Validation failed: " + String.join(", ", ex.getErrors()));
                    });
        }

        return configService
                .testTranslation(issuer, subject, request.claims())
                .map(TranslationTestResultDto::fromModel);
    }

    /**
     * Get the currently active configuration.
     *
     * @return the active configuration or 404 if none is active
     */
    @GET
    @Path("/active")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getActiveConfig() {
        return configService.getActive().map(opt -> opt.map(
                        version -> Response.ok(TranslationConfigVersionDto.fromModel(version))
                                .build())
                .orElseThrow(() -> GatewayProblem.notFound("No active translation configuration")));
    }

    /**
     * List all configuration versions.
     *
     * @param limit maximum number of versions to return
     * @param offset number of versions to skip
     * @return list of version summaries
     */
    @GET
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<List<TranslationConfigVersionSummaryDto>> listVersions(
            @QueryParam("limit") @DefaultValue("50") int limit, @QueryParam("offset") @DefaultValue("0") int offset) {
        return configService.listVersions(limit, offset).map(versions -> versions.stream()
                .map(TranslationConfigVersionSummaryDto::fromModel)
                .toList());
    }

    /**
     * Get a specific configuration version by ID.
     *
     * @param versionId the version ID
     * @return the configuration version or 404 if not found
     */
    @GET
    @Path("/{versionId}")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getVersion(@PathParam("versionId") String versionId) {
        return configService.getById(versionId).map(opt -> opt.map(
                        version -> Response.ok(TranslationConfigVersionDto.fromModel(version))
                                .build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("TranslationConfigVersion", versionId)));
    }

    /**
     * Activate a specific configuration version.
     *
     * @param versionId the version ID to activate
     * @param ctx security context for determining the actor
     * @return 204 No Content if activated, or 404 if not found
     */
    @PUT
    @Path("/{versionId}/activate")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> activateVersion(@PathParam("versionId") String versionId, @Context SecurityContext ctx) {
        final var actor = getUserId(ctx);
        return configService.activate(versionId).map(activated -> {
            if (activated) {
                LOG.infof("Translation config activated: versionId=%s, actor=%s", versionId, actor);
                return Response.noContent().build();
            } else {
                throw GatewayProblem.resourceNotFound("TranslationConfigVersion", versionId);
            }
        });
    }

    /**
     * Rollback to a specific version number.
     *
     * @param versionNumber the version number to rollback to
     * @param ctx security context for determining the actor
     * @return the activated version or 404 if not found
     */
    @POST
    @Path("/rollback/{versionNumber}")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> rollback(@PathParam("versionNumber") int versionNumber, @Context SecurityContext ctx) {
        final var actor = getUserId(ctx);
        return configService
                .rollback(versionNumber)
                .invoke(opt -> opt.ifPresent(version -> LOG.infof(
                        "Translation config rollback: versionNumber=%d, versionId=%s, actor=%s",
                        versionNumber, version.id(), actor)))
                .map(opt -> opt.map(version -> Response.ok(TranslationConfigVersionDto.fromModel(version))
                                .build())
                        .orElseThrow(() -> GatewayProblem.notFound("Version " + versionNumber + " not found")));
    }

    /**
     * Delete a configuration version.
     *
     * <p>Active versions cannot be deleted.
     *
     * @param versionId the version ID to delete
     * @param ctx security context for determining the actor
     * @return 204 No Content if deleted, or 404 if not found
     */
    @DELETE
    @Path("/{versionId}")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> deleteVersion(@PathParam("versionId") String versionId, @Context SecurityContext ctx) {
        final var actor = getUserId(ctx);
        return configService.delete(versionId).map(deleted -> {
            if (deleted) {
                LOG.infof("Translation config deleted: versionId=%s, actor=%s", versionId, actor);
                return Response.noContent().build();
            } else {
                throw GatewayProblem.resourceNotFound("TranslationConfigVersion", versionId);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Introspection Endpoints
    // -------------------------------------------------------------------------

    /**
     * Get the current status of the token translation service.
     *
     * <p>Returns information about the active provider, cache statistics,
     * and overall service health.
     *
     * @return translation service status
     */
    @GET
    @Path("/status")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<TranslationStatusDto> getStatus() {
        return Uni.createFrom()
                .item(TranslationStatusDto.create(
                        translationService.isEnabled(),
                        translationService.getActiveProviderName(),
                        translationService.isProviderHealthy(),
                        translationService.getCacheSize(),
                        translationService.getCacheMaxSize(),
                        translationService.getCacheTtlSeconds()));
    }

    /**
     * Invalidate the translation cache.
     *
     * <p>Forces re-translation for all subsequent token validations.
     * Use this after updating translation configuration to ensure
     * the new configuration takes effect immediately.
     *
     * @param ctx security context for determining the actor
     * @return 204 No Content
     */
    @POST
    @Path("/cache/invalidate")
    @PermissionsAllowed({Permission.TRANSLATION_CONFIG_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> invalidateCache(@Context SecurityContext ctx) {
        final var actor = getUserId(ctx);
        translationService.invalidateCache();
        LOG.infof("Translation cache invalidated: actor=%s", actor);
        return Uni.createFrom().item(Response.noContent().build());
    }

    private String getUserId(SecurityContext ctx) {
        final Principal principal = ctx.getUserPrincipal();
        if (principal != null) {
            return principal.getName();
        }
        return "unknown";
    }
}
