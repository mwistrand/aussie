package aussie.adapter.in.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import aussie.core.model.auth.TranslationConfigSchema;

/**
 * DTO for uploading a new translation configuration.
 *
 * @param config   the translation configuration schema
 * @param comment  optional description of changes
 * @param activate whether to immediately activate this version (defaults to false)
 */
public record TranslationConfigUploadDto(
        @NotNull(message = "config is required") @Valid TranslationConfigSchema config,
        @Size(max = 1000, message = "comment must be 1000 characters or less") String comment,
        boolean activate) {

    public TranslationConfigUploadDto(TranslationConfigSchema config, String comment) {
        this(config, comment, false);
    }
}
