package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.OperationPermission;

@DisplayName("OperationPermissionDto Tests")
class OperationPermissionDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert permissions to model")
        void shouldConvertPermissionsToModel() {
            var dto = new OperationPermissionDto(Set.of("read", "write"));

            var model = dto.toModel();

            assertEquals(Set.of("read", "write"), model.anyOfPermissions());
        }

        @Test
        @DisplayName("Should default null permissions to empty set")
        void shouldDefaultNullPermissionsToEmptySet() {
            var dto = new OperationPermissionDto(null);

            var model = dto.toModel();

            assertTrue(model.anyOfPermissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model to DTO")
        void shouldConvertModelToDto() {
            var model = new OperationPermission(Set.of("admin"));

            var dto = OperationPermissionDto.fromModel(model);

            assertEquals(Set.of("admin"), dto.anyOfPermissions());
        }
    }
}
