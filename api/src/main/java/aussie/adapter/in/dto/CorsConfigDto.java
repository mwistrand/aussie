package aussie.adapter.in.dto;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import aussie.core.model.common.CorsConfig;

/**
 * DTO for CORS configuration in service registration.
 */
public record CorsConfigDto(
        List<String> allowedOrigins,
        Set<String> allowedMethods,
        Set<String> allowedHeaders,
        Set<String> exposedHeaders,
        Boolean allowCredentials,
        @Min(value = 0, message = "maxAge must be non-negative")
                @Max(value = 86400, message = "maxAge must be at most 86400 seconds (24 hours)")
                Long maxAge) {

    /**
     * Convert this DTO to a CorsConfig model.
     */
    public CorsConfig toModel() {
        return new CorsConfig(
                allowedOrigins != null ? allowedOrigins : List.of(),
                allowedMethods != null ? allowedMethods : Set.of(),
                allowedHeaders != null ? allowedHeaders : Set.of(),
                exposedHeaders != null ? exposedHeaders : Set.of(),
                allowCredentials != null ? allowCredentials : false,
                maxAge != null ? Optional.of(maxAge) : Optional.empty());
    }

    /**
     * Create a DTO from a CorsConfig model.
     */
    public static CorsConfigDto fromModel(CorsConfig config) {
        if (config == null) {
            return null;
        }
        return new CorsConfigDto(
                config.allowedOrigins(),
                config.allowedMethods(),
                config.allowedHeaders(),
                config.exposedHeaders(),
                config.allowCredentials(),
                config.maxAge().orElse(null));
    }
}
