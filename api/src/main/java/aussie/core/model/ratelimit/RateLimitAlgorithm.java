package aussie.core.model.ratelimit;

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
    BUCKET
}
