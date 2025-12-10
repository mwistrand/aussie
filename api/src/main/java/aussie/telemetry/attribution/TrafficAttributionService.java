package aussie.telemetry.attribution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.config.TelemetryConfig;
import aussie.telemetry.metrics.GatewayMetrics;

/**
 * Service for recording traffic attribution metrics.
 *
 * <p>This service extracts attribution dimensions from requests and records
 * them as metrics for cost allocation reporting. Metrics can be queried
 * via Prometheus to generate chargeback reports by team, cost center, or tenant.
 *
 * <p>Example PromQL queries:
 * <pre>
 * # Total requests per team
 * sum by (team_id) (increase(aussie_attributed_requests_total[30d]))
 *
 * # Data transfer per cost center (GB)
 * sum by (cost_center) (
 *   increase(aussie_attributed_bytes_ingress[30d]) +
 *   increase(aussie_attributed_bytes_egress[30d])
 * ) / 1e9
 *
 * # Compute units per team per day
 * sum by (team_id) (increase(aussie_attributed_compute_units[1d]))
 * </pre>
 */
@ApplicationScoped
public class TrafficAttributionService {

    @Inject
    TelemetryConfig config;

    @Inject
    GatewayMetrics metrics;

    /**
     * Records an attributed request with all metrics.
     *
     * @param attribution attribution dimensions
     * @param requestMetrics request-level metrics
     */
    public void recordAttributedRequest(TrafficAttribution attribution, RequestMetrics requestMetrics) {
        if (!config.trafficAttribution().enabled()) {
            return;
        }

        metrics.recordTrafficAttribution(
                attribution.serviceId(),
                attribution.teamId(),
                attribution.costCenter(),
                attribution.tenantId(),
                requestMetrics.requestBytes(),
                requestMetrics.responseBytes(),
                requestMetrics.durationMs());
    }

    /**
     * Extracts traffic attribution from request headers.
     *
     * @param serviceId target service ID
     * @param teamHeader value of team header (may be null)
     * @param costCenterHeader value of cost center header (may be null)
     * @param tenantHeader value of tenant header (may be null)
     * @param clientApp client application identifier (may be null)
     * @return TrafficAttribution instance
     */
    public TrafficAttribution extractAttribution(
            String serviceId, String teamHeader, String costCenterHeader, String tenantHeader, String clientApp) {

        String environment = System.getenv("AUSSIE_ENV");
        if (environment == null || environment.isBlank()) {
            environment = "development";
        }

        return TrafficAttribution.builder()
                .serviceId(serviceId)
                .teamId(teamHeader)
                .costCenter(costCenterHeader)
                .tenantId(tenantHeader)
                .clientApplication(clientApp)
                .environment(environment)
                .build();
    }

    /**
     * Returns the configured team header name.
     *
     * @return team header name
     */
    public String getTeamHeaderName() {
        return config.trafficAttribution().teamHeader();
    }

    /**
     * Returns the configured cost center header name.
     *
     * @return cost center header name
     */
    public String getCostCenterHeaderName() {
        return config.trafficAttribution().costCenterHeader();
    }

    /**
     * Returns the configured tenant header name.
     *
     * @return tenant header name
     */
    public String getTenantHeaderName() {
        return config.trafficAttribution().tenantHeader();
    }
}
