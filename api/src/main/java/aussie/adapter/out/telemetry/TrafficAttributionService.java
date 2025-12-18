package aussie.adapter.out.telemetry;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.TrafficAttributing;

/**
 * Service for recording traffic attribution metrics for cost allocation.
 *
 * <p>This service records metrics with dimensional tags that enable
 * filtering and grouping by team, tenant, service, and other dimensions
 * for billing and reporting purposes.
 *
 * <p>Metrics recorded:
 * <ul>
 *   <li>{@code aussie.attributed.requests.total} - Request count</li>
 *   <li>{@code aussie.attributed.bytes.ingress} - Incoming data volume</li>
 *   <li>{@code aussie.attributed.bytes.egress} - Outgoing data volume</li>
 *   <li>{@code aussie.attributed.compute.units} - Normalized compute cost</li>
 *   <li>{@code aussie.attributed.duration} - Request duration</li>
 * </ul>
 *
 * <p>All metrics are tagged with:
 * <ul>
 *   <li>{@code service_id} - Target service</li>
 *   <li>{@code team_id} - Owning/consuming team</li>
 *   <li>{@code tenant_id} - Multi-tenant identifier</li>
 *   <li>{@code environment} - Deployment environment</li>
 * </ul>
 */
@ApplicationScoped
public class TrafficAttributionService implements TrafficAttributing {

    private final MeterRegistry registry;
    private final TelemetryConfig config;
    private final boolean enabled;

    @Inject
    public TrafficAttributionService(MeterRegistry registry, TelemetryConfig config) {
        this.registry = registry;
        this.config = config;
        this.enabled =
                config != null && config.enabled() && config.attribution().enabled();
    }

    /**
     * Check if traffic attribution is enabled.
     *
     * @return true if attribution is enabled
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the attribution configuration.
     *
     * @return attribution config
     */
    public TelemetryConfig.AttributionConfig getConfig() {
        return config.attribution();
    }

    /**
     * Record attributed traffic for a completed request.
     *
     * @param request the gateway request
     * @param service the target service
     * @param requestBodySize request body size in bytes
     * @param responseBodySize response body size in bytes
     * @param durationMs request duration in milliseconds
     */
    @Override
    public void record(
            GatewayRequest request,
            ServiceRegistration service,
            long requestBodySize,
            long responseBodySize,
            long durationMs) {
        if (!enabled) {
            return;
        }

        var attribution = TrafficAttribution.from(request, service, config.attribution());
        var metrics = new RequestMetrics(requestBodySize, responseBodySize, durationMs);
        recordAttributedRequest(attribution, metrics);
    }

    /**
     * Record an attributed request.
     *
     * @param attribution the attribution dimensions
     * @param metrics the request metrics
     */
    public void recordAttributedRequest(TrafficAttribution attribution, RequestMetrics metrics) {
        if (!enabled) {
            return;
        }

        var tags = buildTags(attribution);

        // Request count
        Counter.builder("aussie.attributed.requests.total")
                .description("Total attributed requests")
                .tags(tags)
                .register(registry)
                .increment();

        // Data transfer
        Counter.builder("aussie.attributed.bytes.ingress")
                .description("Attributed incoming data volume")
                .tags(tags)
                .register(registry)
                .increment(metrics.requestBytes());

        Counter.builder("aussie.attributed.bytes.egress")
                .description("Attributed outgoing data volume")
                .tags(tags)
                .register(registry)
                .increment(metrics.responseBytes());

        // Compute units (normalized cost)
        var computeUnits = calculateComputeUnits(metrics);
        Counter.builder("aussie.attributed.compute.units")
                .description("Attributed compute units")
                .tags(tags)
                .register(registry)
                .increment(computeUnits);

        // Request duration
        Timer.builder("aussie.attributed.duration")
                .description("Attributed request duration")
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(metrics.durationMs(), TimeUnit.MILLISECONDS);
    }

    private Tags buildTags(TrafficAttribution attribution) {
        return Tags.of(
                "service_id", attribution.serviceIdOrUnknown(),
                "team_id", attribution.teamIdOrUnknown(),
                "tenant_id", attribution.tenantIdOrUnknown(),
                "environment", attribution.environmentOrUnknown());
    }

    /**
     * Calculate compute units from request metrics.
     *
     * <p>Compute units are a normalized cost metric that accounts for:
     * <ul>
     *   <li>Base cost per request (1.0)</li>
     *   <li>Data transfer cost (bytes / 10KB)</li>
     *   <li>Processing time cost (duration / 100ms)</li>
     * </ul>
     *
     * @param metrics the request metrics
     * @return compute units
     */
    private double calculateComputeUnits(RequestMetrics metrics) {
        // Base cost per request
        double baseCost = 1.0;

        // Data transfer cost (1 unit per 10KB)
        double dataCost = (metrics.requestBytes() + metrics.responseBytes()) / 10_000.0;

        // Processing time cost (1 unit per 100ms)
        double timeCost = metrics.durationMs() / 100.0;

        return baseCost + dataCost + timeCost;
    }

    /**
     * Metrics for a single request.
     *
     * @param requestBytes size of request body in bytes
     * @param responseBytes size of response body in bytes
     * @param durationMs request processing time in milliseconds
     */
    public record RequestMetrics(long requestBytes, long responseBytes, long durationMs) {}
}
