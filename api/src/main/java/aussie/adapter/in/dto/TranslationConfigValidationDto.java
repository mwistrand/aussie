package aussie.adapter.in.dto;

import java.util.List;

/**
 * DTO for translation configuration validation results.
 *
 * @param valid whether the configuration is valid
 * @param errors list of validation errors (empty if valid)
 */
public record TranslationConfigValidationDto(boolean valid, List<String> errors) {

    /**
     * Creates a successful validation result.
     */
    public static TranslationConfigValidationDto success() {
        return new TranslationConfigValidationDto(true, List.of());
    }

    /**
     * Creates a failed validation result.
     */
    public static TranslationConfigValidationDto failure(List<String> errors) {
        return new TranslationConfigValidationDto(false, errors);
    }
}
