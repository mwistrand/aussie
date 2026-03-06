package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.OperationPermission;
import aussie.core.model.auth.ServicePermissionPolicy;

@DisplayName("ServicePermissionPolicyDto Tests")
class ServicePermissionPolicyDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert permissions map to model")
        void shouldConvertPermissionsMapToModel() {
            var dto = new ServicePermissionPolicyDto(Map.of("read", new OperationPermissionDto(Set.of("viewer"))));

            var model = dto.toModel();

            assertTrue(model.hasPermissions());
            assertEquals(Set.of("viewer"), model.permissions().get("read").anyOfPermissions());
        }

        @Test
        @DisplayName("Should return empty policy for null permissions")
        void shouldReturnEmptyPolicyForNullPermissions() {
            var dto = new ServicePermissionPolicyDto(null);

            var model = dto.toModel();

            assertFalse(model.hasPermissions());
        }

        @Test
        @DisplayName("Should return empty policy for empty permissions")
        void shouldReturnEmptyPolicyForEmptyPermissions() {
            var dto = new ServicePermissionPolicyDto(Map.of());

            var model = dto.toModel();

            assertFalse(model.hasPermissions());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model with permissions to DTO")
        void shouldConvertModelWithPermissionsToDto() {
            var model = new ServicePermissionPolicy(Map.of("write", new OperationPermission(Set.of("editor"))));

            var dto = ServicePermissionPolicyDto.fromModel(model);

            assertNotNull(dto);
            assertEquals(Set.of("editor"), dto.permissions().get("write").anyOfPermissions());
        }

        @Test
        @DisplayName("Should return null for null model")
        void shouldReturnNullForNullModel() {
            assertNull(ServicePermissionPolicyDto.fromModel(null));
        }

        @Test
        @DisplayName("Should return null for empty policy")
        void shouldReturnNullForEmptyPolicy() {
            assertNull(ServicePermissionPolicyDto.fromModel(ServicePermissionPolicy.empty()));
        }
    }
}
