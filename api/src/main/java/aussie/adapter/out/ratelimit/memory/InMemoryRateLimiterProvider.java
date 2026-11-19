package aussie.adapter.out.ratelimit.memory;

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
    private static final long DEFAULT_WINDOW_SECONDS = 60;

    private final boolean enabled;
    private final long windowSeconds;

    /**
     * Create a new in-memory provider with configuration.
     *
     * @param enabled whether rate limiting is enabled
     * @param windowSeconds the rate limit window duration in seconds
     */
    public InMemoryRateLimiterProvider(boolean enabled, long windowSeconds) {
        this.enabled = enabled;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Default constructor for ServiceLoader.
     *
     * <p>When loaded via ServiceLoader, configuration must be injected
     * separately via the loader.
     */
    public InMemoryRateLimiterProvider() {
        this.enabled = true;
        this.windowSeconds = DEFAULT_WINDOW_SECONDS;
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
        return new InMemoryRateLimiter(enabled, windowSeconds);
    }

    /**
     * Create a configured provider instance.
     *
     * @param enabled whether rate limiting is enabled
     * @param windowSeconds the rate limit window duration in seconds
     * @return the configured provider
     */
    public static InMemoryRateLimiterProvider configured(boolean enabled, long windowSeconds) {
        return new InMemoryRateLimiterProvider(enabled, windowSeconds);
    }
}
