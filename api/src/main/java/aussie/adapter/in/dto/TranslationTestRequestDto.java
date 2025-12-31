package aussie.adapter.in.dto;

import java.util.Map;

import aussie.core.model.auth.TranslationConfigSchema;

/**
 * DTO for testing translation with sample claims.
 *
 * @param config optional configuration to test (uses active config if null)
 * @param issuer the token issuer
 * @param subject the token subject
 * @param claims the sample claims to translate
 */
public record TranslationTestRequestDto(
        TranslationConfigSchema config, String issuer, String subject, Map<String, Object> claims) {

    /**
     * Creates a test request using the active configuration.
     */
    public static TranslationTestRequestDto withActivConfig(String issuer, String subject, Map<String, Object> claims) {
        return new TranslationTestRequestDto(null, issuer, subject, claims);
    }
}
