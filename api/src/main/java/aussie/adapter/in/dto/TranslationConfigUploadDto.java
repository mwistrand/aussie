package aussie.adapter.in.dto;

import aussie.core.model.auth.TranslationConfigSchema;

/**
 * DTO for uploading a new translation configuration.
 *
 * @param config   the translation configuration schema
 * @param comment  optional description of changes
 * @param activate whether to immediately activate this version
 */
public record TranslationConfigUploadDto(TranslationConfigSchema config, String comment, boolean activate) {

    public TranslationConfigUploadDto(TranslationConfigSchema config, String comment) {
        this(config, comment, true);
    }
}
