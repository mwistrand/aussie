package aussie.adapter.in.dto;

import java.util.List;
import java.util.Optional;

import aussie.core.model.ServiceAccessConfig;

public record ServiceAccessConfigDto(
    List<String> allowedIps,
    List<String> allowedDomains,
    List<String> allowedSubdomains
) {
    public ServiceAccessConfig toModel() {
        return new ServiceAccessConfig(
            Optional.ofNullable(allowedIps),
            Optional.ofNullable(allowedDomains),
            Optional.ofNullable(allowedSubdomains)
        );
    }

    public static ServiceAccessConfigDto fromModel(ServiceAccessConfig model) {
        return new ServiceAccessConfigDto(
            model.allowedIps().orElse(null),
            model.allowedDomains().orElse(null),
            model.allowedSubdomains().orElse(null)
        );
    }
}
