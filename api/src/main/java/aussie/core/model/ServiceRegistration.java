package aussie.core.model;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public record ServiceRegistration(
        String serviceId,
        String displayName,
        URI baseUrl,
        String routePrefix,
        EndpointVisibility defaultVisibility,
        boolean defaultAuthRequired,
        List<VisibilityRule> visibilityRules,
        List<EndpointConfig> endpoints,
        Optional<ServiceAccessConfig> accessConfig,
        Optional<CorsConfig> corsConfig) {
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
        if (routePrefix == null || routePrefix.isBlank()) {
            routePrefix = "/" + serviceId;
        }
        if (defaultVisibility == null) {
            defaultVisibility = EndpointVisibility.PRIVATE;
        }
        if (visibilityRules == null) {
            visibilityRules = List.of();
        }
        if (endpoints == null) {
            endpoints = List.of();
        }
        if (accessConfig == null) {
            accessConfig = Optional.empty();
        }
        if (corsConfig == null) {
            corsConfig = Optional.empty();
        }
    }

    public static Builder builder(String serviceId) {
        return new Builder(serviceId);
    }

    public static class Builder {
        private final String serviceId;
        private String displayName;
        private URI baseUrl;
        private String routePrefix;
        private EndpointVisibility defaultVisibility;
        private boolean defaultAuthRequired = true;
        private List<VisibilityRule> visibilityRules = List.of();
        private List<EndpointConfig> endpoints = List.of();
        private ServiceAccessConfig accessConfig;
        private CorsConfig corsConfig;

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

        public Builder routePrefix(String routePrefix) {
            this.routePrefix = routePrefix;
            return this;
        }

        public Builder defaultVisibility(EndpointVisibility defaultVisibility) {
            this.defaultVisibility = defaultVisibility;
            return this;
        }

        public Builder defaultAuthRequired(boolean defaultAuthRequired) {
            this.defaultAuthRequired = defaultAuthRequired;
            return this;
        }

        public Builder visibilityRules(List<VisibilityRule> visibilityRules) {
            this.visibilityRules = visibilityRules;
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

        public Builder corsConfig(CorsConfig corsConfig) {
            this.corsConfig = corsConfig;
            return this;
        }

        public ServiceRegistration build() {
            return new ServiceRegistration(
                    serviceId,
                    displayName != null ? displayName : serviceId,
                    baseUrl,
                    routePrefix,
                    defaultVisibility,
                    defaultAuthRequired,
                    visibilityRules,
                    endpoints,
                    Optional.ofNullable(accessConfig),
                    Optional.ofNullable(corsConfig));
        }
    }
}
