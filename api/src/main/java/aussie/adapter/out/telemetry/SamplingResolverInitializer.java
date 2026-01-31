package aussie.adapter.out.telemetry;

import java.util.ArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import aussie.core.config.SamplingConfig;

/**
 * Initializes the SamplingResolverHolder with the CDI-managed SamplingResolver.
 *
 * <p>This bean observes Quarkus startup and shutdown events to manage the
 * lifecycle of the static holder. This pattern is necessary because the
 * OTel Sampler is instantiated by the OTel SPI before CDI is available.
 *
 * <p>On startup, this initializer validates the sampling configuration to
 * ensure all rates are within valid bounds and logically consistent.
 */
@ApplicationScoped
public class SamplingResolverInitializer {

    private static final Logger LOG = Logger.getLogger(SamplingResolverInitializer.class);

    private final SamplingResolver samplingResolver;
    private final SamplingConfig samplingConfig;

    @Inject
    public SamplingResolverInitializer(SamplingResolver samplingResolver, SamplingConfig samplingConfig) {
        this.samplingResolver = samplingResolver;
        this.samplingConfig = samplingConfig;
    }

    /**
     * Set the resolver in the static holder on application startup.
     *
     * <p>Also validates the sampling configuration to fail fast on misconfiguration.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        if (samplingConfig.enabled()) {
            validateConfiguration();
        }
        SamplingResolverHolder.set(samplingResolver);
    }

    /**
     * Validate sampling configuration invariants.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    private void validateConfiguration() {
        final var errors = new ArrayList<String>();
        final var minRate = samplingConfig.minimumRate();
        final var maxRate = samplingConfig.maximumRate();
        final var defaultRate = samplingConfig.defaultRate();

        // Validate all rates are within [0.0, 1.0]
        if (minRate < 0.0 || minRate > 1.0) {
            errors.add("minimumRate must be between 0.0 and 1.0, got: " + minRate);
        }
        if (maxRate < 0.0 || maxRate > 1.0) {
            errors.add("maximumRate must be between 0.0 and 1.0, got: " + maxRate);
        }
        if (defaultRate < 0.0 || defaultRate > 1.0) {
            errors.add("defaultRate must be between 0.0 and 1.0, got: " + defaultRate);
        }

        // Validate logical consistency: min <= max
        if (minRate > maxRate) {
            errors.add("minimumRate (" + minRate + ") must be <= maximumRate (" + maxRate + ")");
        }

        // Validate default is within bounds (will be clamped, but warn if misconfigured)
        if (defaultRate < minRate || defaultRate > maxRate) {
            LOG.warnf("defaultRate (%f) is outside bounds [%f, %f] and will be clamped", defaultRate, minRate, maxRate);
        }

        if (!errors.isEmpty()) {
            final var message = "Invalid sampling configuration:\n  - " + String.join("\n  - ", errors);
            LOG.error(message);
            throw new IllegalStateException(message);
        }

        LOG.debugf("Sampling configuration validated: default=%f, min=%f, max=%f", defaultRate, minRate, maxRate);
    }

    /**
     * Clear the resolver from the static holder on application shutdown.
     *
     * @param event the shutdown event
     */
    void onStop(@Observes ShutdownEvent event) {
        SamplingResolverHolder.clear();
    }
}
