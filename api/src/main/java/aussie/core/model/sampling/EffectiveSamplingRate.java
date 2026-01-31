package aussie.core.model.sampling;

/**
 * Represents the resolved sampling rate after applying hierarchical overrides.
 *
 * <p>This record contains both the effective rate and the source that determined
 * the rate, which is useful for debugging and observability.
 *
 * @param rate the effective sampling rate (0.0 to 1.0)
 * @param source where this rate came from (PLATFORM, SERVICE, or ENDPOINT)
 */
public record EffectiveSamplingRate(double rate, SamplingSource source) {

    /**
     * Small epsilon value for floating-point comparisons.
     *
     * <p>This accounts for minor precision drift that can occur during
     * configuration parsing or arithmetic operations on sampling rates.
     */
    private static final double EPSILON = 1e-9;

    /**
     * The source of a sampling rate decision.
     */
    public enum SamplingSource {
        /** Rate came from platform configuration defaults */
        PLATFORM,
        /** Rate came from service-level configuration */
        SERVICE,
        /** Rate came from endpoint-level configuration */
        ENDPOINT
    }

    public EffectiveSamplingRate {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Sampling rate must be between 0.0 and 1.0, got: " + rate);
        }
        if (source == null) {
            throw new IllegalArgumentException("Source cannot be null");
        }
    }

    /**
     * Clamp the rate to the platform minimum and maximum bounds.
     *
     * @param minimum the platform minimum rate
     * @param maximum the platform maximum rate
     * @return a new EffectiveSamplingRate with the rate clamped to bounds
     */
    public EffectiveSamplingRate clampToPlatformBounds(double minimum, double maximum) {
        final var clampedRate = Math.max(minimum, Math.min(maximum, rate));
        return new EffectiveSamplingRate(clampedRate, source);
    }

    /**
     * Check if this rate indicates that all requests should be traced.
     *
     * <p>Uses epsilon comparison to handle floating-point precision issues
     * that may arise from configuration parsing.
     *
     * @return true if rate is effectively 1.0 (no sampling)
     */
    public boolean isNoSampling() {
        return rate >= 1.0 - EPSILON;
    }

    /**
     * Check if this rate indicates that no requests should be traced.
     *
     * <p>Uses epsilon comparison to handle floating-point precision issues
     * that may arise from configuration parsing.
     *
     * @return true if rate is effectively 0.0
     */
    public boolean isDropAll() {
        return rate <= EPSILON;
    }
}
