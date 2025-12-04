package aussie.adapter.in.dto;

import java.util.List;

import aussie.core.model.ServiceRegistration;

public record ServiceRegistrationResponse(
        String serviceId,
        String displayName,
        String baseUrl,
        String routePrefix,
        String defaultVisibility,
        List<VisibilityRuleDto> visibilityRules,
        List<EndpointConfigDto> endpoints,
        ServiceAccessConfigDto accessConfig) {
    public static ServiceRegistrationResponse fromModel(ServiceRegistration model) {
        var visibilityRuleDtos = model.visibilityRules().isEmpty()
                ? null
                : model.visibilityRules().stream()
                        .map(VisibilityRuleDto::fromModel)
                        .toList();

        var endpointDtos = model.endpoints().isEmpty()
                ? null
                : model.endpoints().stream().map(EndpointConfigDto::fromModel).toList();

        var accessConfigDto =
                model.accessConfig().map(ServiceAccessConfigDto::fromModel).orElse(null);

        return new ServiceRegistrationResponse(
                model.serviceId(),
                model.displayName(),
                model.baseUrl().toString(),
                model.routePrefix(),
                model.defaultVisibility().name(),
                visibilityRuleDtos,
                endpointDtos,
                accessConfigDto);
    }
}
