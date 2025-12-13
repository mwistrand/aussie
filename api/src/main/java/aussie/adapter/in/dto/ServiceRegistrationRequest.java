package aussie.adapter.in.dto;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import aussie.core.model.CorsConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.VisibilityRule;

public record ServiceRegistrationRequest(
        Long version,
        String serviceId,
        String displayName,
        String baseUrl,
        String routePrefix,
        String defaultVisibility,
        Boolean defaultAuthRequired,
        List<VisibilityRuleDto> visibilityRules,
        List<EndpointConfigDto> endpoints,
        ServiceAccessConfigDto accessConfig,
        CorsConfigDto cors,
        ServicePermissionPolicyDto permissionPolicy,
        ServiceRateLimitConfigDto rateLimitConfig) {
    public ServiceRegistration toModel() {
        var defaultVis = defaultVisibility != null
                ? EndpointVisibility.valueOf(defaultVisibility.toUpperCase())
                : EndpointVisibility.PRIVATE;

        var defaultAuth = defaultAuthRequired != null ? defaultAuthRequired : true;

        var visibilityRuleModels = visibilityRules != null
                ? visibilityRules.stream().map(VisibilityRuleDto::toModel).toList()
                : List.<VisibilityRule>of();

        var endpointModels = endpoints != null
                ? endpoints.stream().map(e -> e.toModel(defaultAuth)).toList()
                : List.<aussie.core.model.EndpointConfig>of();

        var accessConfigModel = accessConfig != null
                ? Optional.of(accessConfig.toModel())
                : Optional.<aussie.core.model.ServiceAccessConfig>empty();

        var corsConfigModel = cors != null ? Optional.of(cors.toModel()) : Optional.<CorsConfig>empty();

        var permissionPolicyModel = permissionPolicy != null
                ? Optional.of(permissionPolicy.toModel())
                : Optional.<aussie.core.model.ServicePermissionPolicy>empty();

        var rateLimitConfigModel = rateLimitConfig != null
                ? Optional.of(rateLimitConfig.toModel())
                : Optional.<aussie.core.model.ServiceRateLimitConfig>empty();

        return new ServiceRegistration(
                serviceId,
                displayName != null ? displayName : serviceId,
                URI.create(baseUrl),
                routePrefix,
                defaultVis,
                defaultAuth,
                visibilityRuleModels,
                endpointModels,
                accessConfigModel,
                corsConfigModel,
                permissionPolicyModel,
                rateLimitConfigModel,
                version == null ? 1L : version); // New registrations start at version 1
    }
}
