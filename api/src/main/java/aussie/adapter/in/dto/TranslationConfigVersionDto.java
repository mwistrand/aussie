package aussie.adapter.in.dto;

import java.time.Instant;

import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;

/**
 * DTO for translation configuration version responses.
 *
 * @param id unique version identifier
 * @param version sequential version number
 * @param config the translation configuration schema
 * @param active whether this version is currently active
 * @param createdBy the user who created this version
 * @param createdAt when this version was created
 * @param comment optional description of changes
 */
public record TranslationConfigVersionDto(
        String id,
        int version,
        TranslationConfigSchema config,
        boolean active,
        String createdBy,
        Instant createdAt,
        String comment) {

    /**
     * Creates a DTO from the domain model.
     */
    public static TranslationConfigVersionDto fromModel(TranslationConfigVersion model) {
        return new TranslationConfigVersionDto(
                model.id(),
                model.version(),
                model.config(),
                model.active(),
                model.createdBy(),
                model.createdAt(),
                model.comment());
    }
}
