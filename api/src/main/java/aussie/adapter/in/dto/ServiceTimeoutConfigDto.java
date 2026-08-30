package aussie.adapter.in.dto;

import java.time.Duration;
import java.util.Optional;

import jakarta.validation.constraints.Pattern;

import aussie.core.model.timeout.ServiceTimeoutConfig;

/**
 * DTO for service timeout configuration in service registration.
 *
 * <p>Maps between the JSON representation and the domain model.
 * Durations use ISO-8601 format (e.g., "PT30S" for 30 seconds, "PT2M" for 2 minutes).
 *
 * @param requestTimeout maximum time to wait for upstream response (ISO-8601 duration)
 */
public record ServiceTimeoutConfigDto(
        @Pattern(
                regexp = "^PT(?:\\d+H)?(?:\\d+M)?(?:\\d+(?:\\.\\d+)?S)?$",
                message = "requestTimeout must be an ISO-8601 duration (e.g., PT30S, PT2M, PT1M30S)")
        String requestTimeout) {

    /**
     * Convert this DTO to a ServiceTimeoutConfig model.
     *
     * @return the domain model
     */
    public ServiceTimeoutConfig toModel() {
        return new ServiceTimeoutConfig(Optional.ofNullable(requestTimeout).map(Duration::parse));
    }

    /**
     * Create a DTO from a ServiceTimeoutConfig model.
     *
     * @param model the domain model (may be null)
     * @return the DTO representation, or null if model is null
     */
    public static ServiceTimeoutConfigDto fromModel(ServiceTimeoutConfig model) {
        if (model == null) {
            return null;
        }
        return new ServiceTimeoutConfigDto(
                model.requestTimeout().map(Duration::toString).orElse(null));
    }
}
