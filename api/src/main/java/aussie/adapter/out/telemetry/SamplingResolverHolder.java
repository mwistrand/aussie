package aussie.adapter.out.telemetry;

import java.util.Optional;

/**
 * Static holder for SamplingResolver to enable access from OTel Sampler.
 *
 * <p>The OTel Sampler is instantiated by the OTel SPI before CDI is fully
 * initialized. This holder allows the sampler to access the resolver once
 * it becomes available.
 *
 * <p><b>Thread safety:</b> Uses volatile for visibility across threads.
 * The resolver is set once at startup and read many times during request
 * processing.
 */
public final class SamplingResolverHolder {

    private static volatile SamplingResolver instance;

    private SamplingResolverHolder() {
        // Utility class
    }

    /**
     * Set the SamplingResolver instance.
     *
     * <p>Called by SamplingResolverInitializer on application startup.
     *
     * @param resolver the resolver instance
     */
    public static void set(SamplingResolver resolver) {
        instance = resolver;
    }

    /**
     * Get the SamplingResolver instance.
     *
     * @return Optional containing the resolver if set, empty otherwise
     */
    public static Optional<SamplingResolver> get() {
        return Optional.ofNullable(instance);
    }

    /**
     * Clear the SamplingResolver instance.
     *
     * <p>Called by SamplingResolverInitializer on application shutdown.
     */
    public static void clear() {
        instance = null;
    }
}
