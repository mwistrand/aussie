package aussie.api.dto;

import java.util.List;

import aussie.routing.model.ServiceRegistration;

public record ServiceRegistrationResponse(
    String serviceId,
    String displayName,
    String baseUrl,
    List<EndpointConfigDto> endpoints,
    ServiceAccessConfigDto accessConfig
) {
    public static ServiceRegistrationResponse fromModel(ServiceRegistration model) {
        var endpointDtos = model.endpoints().stream()
            .map(EndpointConfigDto::fromModel)
            .toList();

        var accessConfigDto = model.accessConfig()
            .map(ServiceAccessConfigDto::fromModel)
            .orElse(null);

        return new ServiceRegistrationResponse(
            model.serviceId(),
            model.displayName(),
            model.baseUrl().toString(),
            endpointDtos,
            accessConfigDto
        );
    }
}
