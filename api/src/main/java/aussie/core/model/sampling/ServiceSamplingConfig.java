package aussie.core.model.sampling;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sampling configuration for a service.
 *
 * <p>Service-level sampling rates apply to all endpoints in the service unless
 * overridden by endpoint-specific configuration.
 *
 * <p>All values are optional; when not specified, platform defaults apply.
 *
 * @param samplingRate sampling rate (0.0 to 1.0), where 1.0 means no sampling (100% trace rate)
 */
public record ServiceSamplingConfig(@JsonProperty("samplingRate") Optional<Double> samplingRate) {

    @JsonCreator
    public ServiceSamplingConfig {
        samplingRate = samplingRate != null ? samplingRate : Optional.empty();

        // Validate rate if present
        samplingRate.ifPresent(rate -> {
            if (rate < 0.0 || rate > 1.0) {
                throw new IllegalArgumentException("Sampling rate must be between 0.0 and 1.0, got: " + rate);
            }
        });
    }

    /**
     * Create a sampling config with rate specified.
     *
     * @param samplingRate the sampling rate (0.0 to 1.0)
     * @return a new ServiceSamplingConfig
     */
    public static ServiceSamplingConfig of(double samplingRate) {
        return new ServiceSamplingConfig(Optional.of(samplingRate));
    }

    /**
     * Create a "no sampling" config (100% trace rate).
     *
     * <p>Use this to explicitly override a parent configuration to ensure
     * all requests are traced.
     *
     * @return a config with samplingRate = 1.0
     */
    public static ServiceSamplingConfig noSampling() {
        return new ServiceSamplingConfig(Optional.of(1.0));
    }

    /**
     * Create an empty configuration (use platform defaults).
     *
     * @return a config with no sampling rate specified
     */
    public static ServiceSamplingConfig defaults() {
        return new ServiceSamplingConfig(Optional.empty());
    }

    /**
     * Check if a sampling rate is configured.
     *
     * @return true if samplingRate is present
     */
    public boolean hasConfiguration() {
        return samplingRate.isPresent();
    }
}
