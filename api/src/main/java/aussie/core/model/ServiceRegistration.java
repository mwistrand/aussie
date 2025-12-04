package aussie.core.model;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public record ServiceRegistration(
        String serviceId,
        String displayName,
        URI baseUrl,
        List<EndpointConfig> endpoints,
        Optional<ServiceAccessConfig> accessConfig) {
    public ServiceRegistration {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("Service ID cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be null or blank");
        }
        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL cannot be null");
        }
        if (endpoints == null) {
            endpoints = List.of();
        }
        if (accessConfig == null) {
            accessConfig = Optional.empty();
        }
    }

    public static Builder builder(String serviceId) {
        return new Builder(serviceId);
    }

    public static class Builder {
        private final String serviceId;
        private String displayName;
        private URI baseUrl;
        private List<EndpointConfig> endpoints = List.of();
        private ServiceAccessConfig accessConfig;

        private Builder(String serviceId) {
            this.serviceId = serviceId;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = URI.create(baseUrl);
            return this;
        }

        public Builder endpoints(List<EndpointConfig> endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder accessConfig(ServiceAccessConfig accessConfig) {
            this.accessConfig = accessConfig;
            return this;
        }

        public ServiceRegistration build() {
            return new ServiceRegistration(
                    serviceId,
                    displayName != null ? displayName : serviceId,
                    baseUrl,
                    endpoints,
                    Optional.ofNullable(accessConfig));
        }
    }
}
