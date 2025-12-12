package aussie.adapter.out.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.quarkus.arc.DefaultBean;

/**
 * Provides fallback/default telemetry beans when the real ones aren't available.
 *
 * <p>These beans are only used when the Quarkus OpenTelemetry and Micrometer extensions
 * don't provide their own beans (e.g., when telemetry is disabled or in test mode).
 */
@ApplicationScoped
public class TelemetryFallbackProducer {

    /**
     * Provides a fallback MeterRegistry when Micrometer is disabled.
     *
     * @return a simple in-memory meter registry
     */
    @Produces
    @Singleton
    @DefaultBean
    @Default
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * Provides a fallback Tracer when OpenTelemetry is disabled.
     *
     * @return a no-op tracer
     */
    @Produces
    @Singleton
    @DefaultBean
    @Default
    public Tracer tracer() {
        return OpenTelemetry.noop().getTracer("aussie-noop");
    }

    /**
     * Provides a fallback TextMapPropagator when OpenTelemetry is disabled.
     *
     * @return a no-op propagator
     */
    @Produces
    @Singleton
    @DefaultBean
    @Default
    public TextMapPropagator textMapPropagator() {
        return OpenTelemetry.noop().getPropagators().getTextMapPropagator();
    }
}
