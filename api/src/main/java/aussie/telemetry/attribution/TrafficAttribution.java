package aussie.telemetry.attribution;

/**
 * Immutable record containing traffic attribution dimensions.
 *
 * <p>This record captures all the dimensions needed for cost allocation
 * and chargeback reporting. Values are extracted from request headers
 * and service configuration.
 *
 * @param serviceId unique identifier of the target service
 * @param teamId team responsible for the service (from header or service config)
 * @param costCenter billing/cost center for chargeback
 * @param tenantId tenant identifier for multi-tenant deployments
 * @param clientApplication source application making the request
 * @param environment deployment environment (dev, staging, prod)
 */
public record TrafficAttribution(
        String serviceId,
        String teamId,
        String costCenter,
        String tenantId,
        String clientApplication,
        String environment) {

    /**
     * Creates a builder for TrafficAttribution.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for TrafficAttribution.
     */
    public static class Builder {
        private String serviceId;
        private String teamId;
        private String costCenter;
        private String tenantId;
        private String clientApplication;
        private String environment;

        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        public Builder teamId(String teamId) {
            this.teamId = teamId;
            return this;
        }

        public Builder costCenter(String costCenter) {
            this.costCenter = costCenter;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder clientApplication(String clientApplication) {
            this.clientApplication = clientApplication;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public TrafficAttribution build() {
            return new TrafficAttribution(serviceId, teamId, costCenter, tenantId, clientApplication, environment);
        }
    }
}
