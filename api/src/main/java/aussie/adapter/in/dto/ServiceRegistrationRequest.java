package aussie.adapter.in.dto;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.VisibilityRule;

public record ServiceRegistrationRequest(
        String serviceId,
        String displayName,
        String baseUrl,
        String routePrefix,
        String defaultVisibility,
        List<VisibilityRuleDto> visibilityRules,
        List<EndpointConfigDto> endpoints,
        ServiceAccessConfigDto accessConfig) {
    public ServiceRegistration toModel() {
        var defaultVis = defaultVisibility != null
                ? EndpointVisibility.valueOf(defaultVisibility.toUpperCase())
                : EndpointVisibility.PRIVATE;

        var visibilityRuleModels = visibilityRules != null
                ? visibilityRules.stream().map(VisibilityRuleDto::toModel).toList()
                : List.<VisibilityRule>of();

        var endpointModels = endpoints != null
                ? endpoints.stream().map(EndpointConfigDto::toModel).toList()
                : List.<aussie.core.model.EndpointConfig>of();

        var accessConfigModel = accessConfig != null
                ? Optional.of(accessConfig.toModel())
                : Optional.<aussie.core.model.ServiceAccessConfig>empty();

        return new ServiceRegistration(
                serviceId,
                displayName != null ? displayName : serviceId,
                URI.create(baseUrl),
                routePrefix,
                defaultVis,
                visibilityRuleModels,
                endpointModels,
                accessConfigModel);
    }
}
