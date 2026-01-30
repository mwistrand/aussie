package aussie.adapter.in.dto;

import java.util.Optional;

import aussie.core.model.sampling.ServiceSamplingConfig;

/**
 * DTO for service sampling configuration in service registration.
 *
 * <p>Maps between the JSON representation and the domain model.
 *
 * @param samplingRate sampling rate (0.0 to 1.0), where 1.0 means no sampling
 */
public record ServiceSamplingConfigDto(Double samplingRate) {

    /**
     * Convert this DTO to a ServiceSamplingConfig model.
     *
     * @return the domain model
     */
    public ServiceSamplingConfig toModel() {
        return new ServiceSamplingConfig(Optional.ofNullable(samplingRate));
    }

    /**
     * Create a DTO from a ServiceSamplingConfig model.
     *
     * @param model the domain model (may be null)
     * @return the DTO representation, or null if model is null
     */
    public static ServiceSamplingConfigDto fromModel(ServiceSamplingConfig model) {
        if (model == null) {
            return null;
        }
        return new ServiceSamplingConfigDto(model.samplingRate().orElse(null));
    }
}
