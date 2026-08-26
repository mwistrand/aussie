package aussie.core.service.ratelimit;

import java.util.EnumMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import aussie.core.model.ratelimit.BucketAlgorithm;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.model.ratelimit.RateLimitAlgorithmHandler;

/**
 * Registry for rate limiting algorithm handlers.
 *
 * <p>Provides lookup of algorithm handlers by type. Unsupported algorithms are
 * rejected so backend selection cannot silently change configured semantics.
 *
 * <p>Currently supported algorithms:
 * <ul>
 *   <li>{@link RateLimitAlgorithm#BUCKET} - Token bucket (default)</li>
 * </ul>
 *
 * <p>Future algorithms (not yet implemented):
 * <ul>
 *   <li>{@link RateLimitAlgorithm#FIXED_WINDOW} - Fixed time windows</li>
 *   <li>{@link RateLimitAlgorithm#SLIDING_WINDOW} - Sliding window</li>
 * </ul>
 */
@ApplicationScoped
public class AlgorithmRegistry {

    private static final Logger LOG = Logger.getLogger(AlgorithmRegistry.class);

    private final Map<RateLimitAlgorithm, RateLimitAlgorithmHandler> handlers;
    private final RateLimitAlgorithmHandler defaultHandler;

    /**
     * Create a new algorithm registry with all available handlers.
     */
    public AlgorithmRegistry() {
        this.handlers = new EnumMap<>(RateLimitAlgorithm.class);
        this.defaultHandler = BucketAlgorithm.getInstance();

        registerHandler(BucketAlgorithm.getInstance());
        // Future: registerHandler(FixedWindowAlgorithm.getInstance());
        // Future: registerHandler(SlidingWindowAlgorithm.getInstance());

        LOG.infov("Initialized algorithm registry with {0} handler(s)", handlers.size());
    }

    /**
     * Get the handler for the specified algorithm.
     *
     * @param algorithm the algorithm type
     * @return the algorithm handler
     */
    public RateLimitAlgorithmHandler getHandler(RateLimitAlgorithm algorithm) {
        final var handler = handlers.get(algorithm);
        if (handler == null) {
            throw new IllegalArgumentException("Unsupported rate-limit algorithm: " + algorithm);
        }
        return handler;
    }

    /**
     * Check if a specific algorithm is available.
     *
     * @param algorithm the algorithm type
     * @return true if the algorithm is supported
     */
    public boolean isAvailable(RateLimitAlgorithm algorithm) {
        return handlers.containsKey(algorithm);
    }

    /**
     * Get the default algorithm handler.
     *
     * @return the default handler (token bucket)
     */
    public RateLimitAlgorithmHandler getDefaultHandler() {
        return defaultHandler;
    }

    private void registerHandler(RateLimitAlgorithmHandler handler) {
        handlers.put(handler.algorithm(), handler);
        LOG.debugv("Registered algorithm handler: {0}", handler.algorithm());
    }
}
