package aussie.adapter.out.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

/**
 * Initializes the SamplingResolverHolder with the CDI-managed SamplingResolver.
 *
 * <p>This bean observes Quarkus startup and shutdown events to manage the
 * lifecycle of the static holder. This pattern is necessary because the
 * OTel Sampler is instantiated by the OTel SPI before CDI is available.
 */
@ApplicationScoped
public class SamplingResolverInitializer {

    private final SamplingResolver samplingResolver;

    @Inject
    public SamplingResolverInitializer(SamplingResolver samplingResolver) {
        this.samplingResolver = samplingResolver;
    }

    /**
     * Set the resolver in the static holder on application startup.
     *
     * @param event the startup event
     */
    void onStart(@Observes StartupEvent event) {
        SamplingResolverHolder.set(samplingResolver);
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
