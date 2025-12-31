package aussie.adapter.in.dto;

import java.util.Set;

import aussie.core.model.auth.TranslatedClaims;

/**
 * DTO for translation test results.
 *
 * @param roles the translated roles
 * @param permissions the translated permissions
 */
public record TranslationTestResultDto(Set<String> roles, Set<String> permissions) {

    /**
     * Creates a result DTO from the domain model.
     */
    public static TranslationTestResultDto fromModel(TranslatedClaims model) {
        return new TranslationTestResultDto(model.roles(), model.permissions());
    }
}
