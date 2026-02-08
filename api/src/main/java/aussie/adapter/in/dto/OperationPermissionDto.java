package aussie.adapter.in.dto;

import java.util.Set;

import jakarta.validation.constraints.NotEmpty;

import aussie.core.model.auth.OperationPermission;

/**
 * DTO for operation permission rules.
 *
 * @param anyOfPermissions set of permissions, any one of which grants access
 */
public record OperationPermissionDto(
        @NotEmpty(message = "at least one permission is required") Set<String> anyOfPermissions) {

    public OperationPermission toModel() {
        return new OperationPermission(anyOfPermissions != null ? anyOfPermissions : Set.of());
    }

    public static OperationPermissionDto fromModel(OperationPermission model) {
        return new OperationPermissionDto(model.anyOfPermissions());
    }
}
