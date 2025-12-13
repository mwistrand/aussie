package aussie.core.model;

/**
 * Rate limiting algorithms supported by the gateway.
 *
 * <p>The algorithm is configured at the platform level via environment variables
 * and cannot be overridden by service teams.
 */
public enum RateLimitAlgorithm {

    /**
     * Token bucket algorithm (default).
     *
     * <p>Allows controlled bursts up to the bucket capacity while maintaining
     * a steady refill rate. Best for general-purpose rate limiting where
     * occasional traffic spikes are acceptable.
     */
    BUCKET,

    /**
     * Fixed window algorithm.
     *
     * <p>Counts requests within fixed time windows with a hard cutoff at
     * window boundaries. Simple and predictable, but can allow double the
     * limit at window boundaries.
     */
    FIXED_WINDOW,

    /**
     * Sliding window algorithm.
     *
     * <p>Smooths rate limiting across window boundaries using weighted
     * averages of the current and previous windows. Provides more even
     * rate enforcement without boundary spikes.
     */
    SLIDING_WINDOW
}
