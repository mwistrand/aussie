package aussie.core.model.auth;

import java.time.Instant;

/**
 * Represents a versioned translation configuration.
 *
 * <p>Configs are stored with version numbers for auditing and rollback.
 * Only one config is active at a time.
 *
 * @param id unique identifier for this version (usually UUID)
 * @param version sequential version number
 * @param config the translation configuration schema
 * @param active whether this version is currently active
 * @param createdBy the user who created this version
 * @param createdAt when this version was created
 * @param comment optional description of changes
 */
public record TranslationConfigVersion(
        String id,
        int version,
        TranslationConfigSchema config,
        boolean active,
        String createdBy,
        Instant createdAt,
        String comment) {

    /**
     * Creates a new version with the given config.
     */
    public static TranslationConfigVersion create(
            String id, int version, TranslationConfigSchema config, String createdBy, String comment) {
        return new TranslationConfigVersion(id, version, config, false, createdBy, Instant.now(), comment);
    }

    /**
     * Returns this version marked as active.
     */
    public TranslationConfigVersion activate() {
        return new TranslationConfigVersion(id, version, config, true, createdBy, createdAt, comment);
    }

    /**
     * Returns this version marked as inactive.
     */
    public TranslationConfigVersion deactivate() {
        return new TranslationConfigVersion(id, version, config, false, createdBy, createdAt, comment);
    }
}
