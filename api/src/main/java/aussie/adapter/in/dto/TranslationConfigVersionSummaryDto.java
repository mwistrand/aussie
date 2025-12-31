package aussie.adapter.in.dto;

import java.time.Instant;

import aussie.core.model.auth.TranslationConfigVersion;

/**
 * Simplified DTO for translation configuration version list responses.
 *
 * <p>Does not include the full configuration to reduce payload size.
 *
 * @param id unique version identifier
 * @param version sequential version number
 * @param active whether this version is currently active
 * @param createdBy the user who created this version
 * @param createdAt when this version was created
 * @param comment optional description of changes
 */
public record TranslationConfigVersionSummaryDto(
        String id, int version, boolean active, String createdBy, Instant createdAt, String comment) {

    /**
     * Creates a summary DTO from the domain model.
     */
    public static TranslationConfigVersionSummaryDto fromModel(TranslationConfigVersion model) {
        return new TranslationConfigVersionSummaryDto(
                model.id(), model.version(), model.active(), model.createdBy(), model.createdAt(), model.comment());
    }
}
