package aussie.adapter.out.ratelimit.memory;

import aussie.core.model.ratelimit.AlgorithmRegistry;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.port.out.RateLimiter;
import aussie.spi.RateLimiterProvider;

/**
 * In-memory rate limiter provider.
 *
 * <p>This provider is always available as a fallback. It has the lowest
 * priority (0), so other providers (like Redis) will be preferred when available.
 *
 * <p>Configuration is passed during creation via the loader.
 */
public final class InMemoryRateLimiterProvider implements RateLimiterProvider {

    private static final int PRIORITY = 0;
    private static final String NAME = "memory";

    private final AlgorithmRegistry algorithmRegistry;
    private final RateLimitAlgorithm algorithm;
    private final boolean enabled;

    /**
     * Create a new in-memory provider with configuration.
     *
     * @param algorithmRegistry the algorithm registry
     * @param algorithm the algorithm to use
     * @param enabled whether rate limiting is enabled
     */
    public InMemoryRateLimiterProvider(
            AlgorithmRegistry algorithmRegistry, RateLimitAlgorithm algorithm, boolean enabled) {
        this.algorithmRegistry = algorithmRegistry;
        this.algorithm = algorithm;
        this.enabled = enabled;
    }

    /**
     * Default constructor for ServiceLoader.
     *
     * <p>When loaded via ServiceLoader, configuration must be injected
     * separately via the loader.
     */
    public InMemoryRateLimiterProvider() {
        this.algorithmRegistry = null;
        this.algorithm = RateLimitAlgorithm.BUCKET;
        this.enabled = true;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        // In-memory is always available as fallback
        return true;
    }

    @Override
    public RateLimiter createRateLimiter() {
        if (algorithmRegistry == null) {
            throw new IllegalStateException(
                    "Provider not configured. Use RateLimiterProviderLoader for proper initialization.");
        }
        return new InMemoryRateLimiter(algorithmRegistry, algorithm, enabled);
    }

    /**
     * Create a configured provider instance.
     *
     * @param algorithmRegistry the algorithm registry
     * @param algorithm the algorithm to use
     * @param enabled whether rate limiting is enabled
     * @return the configured provider
     */
    public static InMemoryRateLimiterProvider configured(
            AlgorithmRegistry algorithmRegistry, RateLimitAlgorithm algorithm, boolean enabled) {
        return new InMemoryRateLimiterProvider(algorithmRegistry, algorithm, enabled);
    }
}
