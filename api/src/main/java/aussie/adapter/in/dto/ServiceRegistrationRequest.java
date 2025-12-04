package aussie.adapter.in.dto;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import aussie.core.model.ServiceRegistration;

public record ServiceRegistrationRequest(
    String serviceId,
    String displayName,
    String baseUrl,
    List<EndpointConfigDto> endpoints,
    ServiceAccessConfigDto accessConfig
) {
    public ServiceRegistration toModel() {
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
            endpointModels,
            accessConfigModel
        );
    }
}
