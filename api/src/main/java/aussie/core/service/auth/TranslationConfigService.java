package aussie.core.service.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigRepository;

/**
 * Service for managing token translation configurations.
 *
 * <p>Handles CRUD operations for translation configs with version history,
 * validation, and testing capabilities.
 */
@ApplicationScoped
public class TranslationConfigService {

    private static final Logger LOG = Logger.getLogger(TranslationConfigService.class);

    private final TranslationConfigRepository repository;
    private final TokenTranslationService translationService;

    @Inject
    public TranslationConfigService(
            TranslationConfigRepository repository, TokenTranslationService translationService) {
        this.repository = repository;
        this.translationService = translationService;
    }

    /**
     * Upload a new configuration version.
     *
     * @param config the configuration to upload
     * @param createdBy the user uploading the config
     * @param comment optional description of changes
     * @param activate whether to immediately activate this version
     * @return Uni with the created version
     */
    public Uni<TranslationConfigVersion> upload(
            TranslationConfigSchema config, String createdBy, String comment, boolean activate) {

        return validate(config).flatMap(errors -> {
            if (!errors.isEmpty()) {
                return Uni.createFrom()
                        .failure(new ConfigValidationException("Configuration validation failed", errors));
            }

            return repository.getNextVersionNumber().flatMap(nextVersion -> {
                final var id = UUID.randomUUID().toString();
                var version = TranslationConfigVersion.create(id, nextVersion, config, createdBy, comment);

                if (activate) {
                    version = version.activate();
                }

                final var finalVersion = version;
                return repository.save(finalVersion).flatMap(ignored -> {
                    if (activate) {
                        return repository.setActive(finalVersion.id()).replaceWith(finalVersion);
                    }
                    return Uni.createFrom().item(finalVersion);
                });
            });
        });
    }

    /**
     * Get the currently active configuration.
     *
     * @return Uni with Optional containing the active version
     */
    public Uni<Optional<TranslationConfigVersion>> getActive() {
        return repository.getActive();
    }

    /**
     * Get a specific configuration version by ID.
     *
     * @param versionId the version ID
     * @return Uni with Optional containing the version
     */
    public Uni<Optional<TranslationConfigVersion>> getById(String versionId) {
        return repository.findById(versionId);
    }

    /**
     * Get a specific configuration version by version number.
     *
     * @param versionNumber the sequential version number
     * @return Uni with Optional containing the version
     */
    public Uni<Optional<TranslationConfigVersion>> getByVersion(int versionNumber) {
        return repository.findByVersion(versionNumber);
    }

    /**
     * List all configuration versions.
     *
     * @return Uni with list of all versions
     */
    public Uni<List<TranslationConfigVersion>> listVersions() {
        return repository.listVersions();
    }

    /**
     * List configuration versions with pagination.
     *
     * @param limit maximum number of versions to return
     * @param offset number of versions to skip
     * @return Uni with list of versions
     */
    public Uni<List<TranslationConfigVersion>> listVersions(int limit, int offset) {
        return repository.listVersions(limit, offset);
    }

    /**
     * Activate a specific version, making it the current config.
     *
     * @param versionId the version ID to activate
     * @return Uni with true if version was found and activated
     */
    public Uni<Boolean> activate(String versionId) {
        LOG.infof("Activating translation config version: %s", versionId);
        return repository.setActive(versionId);
    }

    /**
     * Rollback to a previous version by version number.
     *
     * @param versionNumber the version number to rollback to
     * @return Uni with Optional containing the activated version
     */
    public Uni<Optional<TranslationConfigVersion>> rollback(int versionNumber) {
        LOG.infof("Rolling back translation config to version: %d", versionNumber);

        return repository.findByVersion(versionNumber).flatMap(version -> {
            if (version.isEmpty()) {
                return Uni.createFrom().item(Optional.empty());
            }

            return repository.setActive(version.get().id()).map(activated -> {
                if (activated) {
                    return Optional.of(version.get().activate());
                }
                return Optional.empty();
            });
        });
    }

    /**
     * Delete a configuration version.
     *
     * <p>Active versions cannot be deleted.
     *
     * @param versionId the version ID to delete
     * @return Uni with true if deleted, false if not found or active
     */
    public Uni<Boolean> delete(String versionId) {
        return repository.findById(versionId).flatMap(version -> {
            if (version.isEmpty()) {
                return Uni.createFrom().item(false);
            }

            if (version.get().active()) {
                return Uni.createFrom()
                        .failure(new IllegalStateException("Cannot delete active configuration version"));
            }

            return repository.delete(versionId);
        });
    }

    /**
     * Validate a configuration without storing it.
     *
     * @param config the configuration to validate
     * @return Uni with list of validation errors (empty if valid)
     */
    public Uni<List<String>> validate(TranslationConfigSchema config) {
        final var errors = new ArrayList<String>();

        if (config == null) {
            errors.add("Configuration cannot be null");
            return Uni.createFrom().item(errors);
        }

        if (config.version() < 1) {
            errors.add("Version must be at least 1");
        }

        if (config.sources() == null) {
            errors.add("Sources list cannot be null");
        } else {
            validateSources(config.sources(), errors);
        }

        if (config.transforms() != null) {
            validateTransforms(config.transforms(), config.sources(), errors);
        }

        if (config.mappings() == null) {
            errors.add("Mappings cannot be null");
        }

        return Uni.createFrom().item(errors);
    }

    /**
     * Test translation with sample claims using a specific configuration.
     *
     * @param config the configuration to test
     * @param issuer the token issuer
     * @param subject the token subject
     * @param claims the sample claims to translate
     * @return Uni with the translated claims result
     */
    public Uni<TranslatedClaims> testTranslation(
            TranslationConfigSchema config, String issuer, String subject, Map<String, Object> claims) {

        return validate(config).flatMap(errors -> {
            if (!errors.isEmpty()) {
                return Uni.createFrom()
                        .failure(new ConfigValidationException("Configuration validation failed", errors));
            }

            return translationService.translateWithConfig(config, issuer, subject, claims);
        });
    }

    /**
     * Test translation with sample claims using the active configuration.
     *
     * @param issuer the token issuer
     * @param subject the token subject
     * @param claims the sample claims to translate
     * @return Uni with the translated claims result
     */
    public Uni<TranslatedClaims> testTranslation(String issuer, String subject, Map<String, Object> claims) {
        return translationService.translate(issuer, subject, claims);
    }

    private void validateSources(List<TranslationConfigSchema.ClaimSource> sources, List<String> errors) {
        for (int i = 0; i < sources.size(); i++) {
            final var source = sources.get(i);
            if (source.name() == null || source.name().isBlank()) {
                errors.add("Source[" + i + "] name cannot be null or blank");
            }
            if (source.claim() == null || source.claim().isBlank()) {
                errors.add("Source[" + i + "] claim cannot be null or blank");
            }
            if (source.type() == null) {
                errors.add("Source[" + i + "] type cannot be null");
            }
        }
    }

    private void validateTransforms(
            List<TranslationConfigSchema.Transform> transforms,
            List<TranslationConfigSchema.ClaimSource> sources,
            List<String> errors) {

        final var sourceNames = sources != null
                ? sources.stream()
                        .map(TranslationConfigSchema.ClaimSource::name)
                        .toList()
                : List.of();

        for (int i = 0; i < transforms.size(); i++) {
            final var transform = transforms.get(i);
            if (transform.source() == null || transform.source().isBlank()) {
                errors.add("Transform[" + i + "] source cannot be null or blank");
            } else if (!sourceNames.contains(transform.source())) {
                errors.add("Transform[" + i + "] references unknown source: " + transform.source());
            }
            if (transform.operations() == null || transform.operations().isEmpty()) {
                errors.add("Transform[" + i + "] must have at least one operation");
            }
        }
    }

    /**
     * Exception thrown when configuration validation fails.
     */
    public static class ConfigValidationException extends RuntimeException {
        private final List<String> errors;

        public ConfigValidationException(String message, List<String> errors) {
            super(message + ": " + String.join(", ", errors));
            this.errors = errors;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
