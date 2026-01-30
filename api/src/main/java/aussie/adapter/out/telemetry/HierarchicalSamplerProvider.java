package aussie.adapter.out.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.quarkus.arc.DefaultBean;

import aussie.core.config.SamplingConfig;

/**
 * CDI producer that provides the custom HierarchicalSampler to Quarkus OTel.
 *
 * <p>Quarkus OpenTelemetry extension looks for a CDI bean of type {@link Sampler}
 * to use as the tracing sampler. This producer creates our custom sampler when
 * hierarchical sampling is enabled.
 *
 * <p>When hierarchical sampling is disabled, this producer returns a standard
 * trace ID ratio based sampler using the configured default rate.
 */
@ApplicationScoped
public class HierarchicalSamplerProvider {

    private final SamplingConfig config;

    @Inject
    public HierarchicalSamplerProvider(SamplingConfig config) {
        this.config = config;
    }

    /**
     * Produce the sampler bean.
     *
     * <p>If hierarchical sampling is enabled, returns a HierarchicalSampler.
     * Otherwise, returns a standard trace ID ratio sampler.
     *
     * @return the sampler to use for tracing
     */
    @Produces
    @Singleton
    @DefaultBean
    public Sampler sampler() {
        if (config.enabled()) {
            return Sampler.parentBased(new HierarchicalSampler(config.defaultRate()));
        }

        // Hierarchical sampling disabled - use standard sampler
        return Sampler.parentBased(Sampler.traceIdRatioBased(config.defaultRate()));
    }
}
