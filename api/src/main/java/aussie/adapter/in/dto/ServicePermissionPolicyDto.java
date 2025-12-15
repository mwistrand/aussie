package aussie.adapter.in.dto;

import java.util.Map;
import java.util.stream.Collectors;

import aussie.core.model.auth.ServicePermissionPolicy;

/**
 * DTO for service permission policy.
 *
 * @param permissions map of operation names to permission rules
 */
public record ServicePermissionPolicyDto(Map<String, OperationPermissionDto> permissions) {

    public ServicePermissionPolicy toModel() {
        if (permissions == null || permissions.isEmpty()) {
            return ServicePermissionPolicy.empty();
        }

        var modelPermissions = permissions.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toModel()));

        return new ServicePermissionPolicy(modelPermissions);
    }

    public static ServicePermissionPolicyDto fromModel(ServicePermissionPolicy model) {
        if (model == null || !model.hasPermissions()) {
            return null;
        }

        var dtoPermissions = model.permissions().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> OperationPermissionDto.fromModel(e.getValue())));

        return new ServicePermissionPolicyDto(dtoPermissions);
    }
}
