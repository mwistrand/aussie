package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.*;

@DisplayName("OperationPermission")
class OperationPermissionTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should handle null anyOfPermissions by creating empty set")
        void shouldHandleNullAnyOfPermissions() {
            var permission = new OperationPermission(null);
            assertNotNull(permission.anyOfPermissions());
            assertTrue(permission.anyOfPermissions().isEmpty());
        }

        @Test
        @DisplayName("Should preserve provided permissions")
        void shouldPreserveProvidedPermissions() {
            var permissions = Set.of("admin", "user");
            var permission = new OperationPermission(permissions);
            assertEquals(permissions, permission.anyOfPermissions());
        }
    }

    @Nested
    @DisplayName("isAllowed")
    class IsAllowedTests {

        @Test
        @DisplayName("Should return true when single permission matches")
        void shouldReturnTrueWhenSinglePermissionMatches() {
            var permission = new OperationPermission(Set.of("admin", "user"));
            assertTrue(permission.isAllowed(Set.of("admin")));
        }

        @Test
        @DisplayName("Should return true when any permission matches")
        void shouldReturnTrueWhenAnyPermissionMatches() {
            var permission = new OperationPermission(Set.of("admin"));
            assertTrue(permission.isAllowed(Set.of("viewer", "admin", "writer")));
        }

        @Test
        @DisplayName("Should return true when multiple permissions match")
        void shouldReturnTrueWhenMultiplePermissionsMatch() {
            var permission = new OperationPermission(Set.of("admin", "user", "viewer"));
            assertTrue(permission.isAllowed(Set.of("user", "admin")));
        }

        @Test
        @DisplayName("Should return false when no permissions match")
        void shouldReturnFalseWhenNoPermissionsMatch() {
            var permission = new OperationPermission(Set.of("admin", "user"));
            assertFalse(permission.isAllowed(Set.of("viewer", "writer")));
        }

        @Test
        @DisplayName("Should return false when permissions is null")
        void shouldReturnFalseWhenPermissionsIsNull() {
            var permission = new OperationPermission(Set.of("admin"));
            assertFalse(permission.isAllowed(null));
        }

        @Test
        @DisplayName("Should return false when permissions is empty")
        void shouldReturnFalseWhenPermissionsIsEmpty() {
            var permission = new OperationPermission(Set.of("admin"));
            assertFalse(permission.isAllowed(Set.of()));
        }

        @Test
        @DisplayName("Should return false when anyOfPermissions is empty")
        void shouldReturnFalseWhenAnyOfPermissionsIsEmpty() {
            var permission = new OperationPermission(Set.of());
            assertFalse(permission.isAllowed(Set.of("admin")));
        }

        @Test
        @DisplayName("Should return false when both permissions and anyOfPermissions are empty")
        void shouldReturnFalseWhenBothEmpty() {
            var permission = new OperationPermission(Set.of());
            assertFalse(permission.isAllowed(Set.of()));
        }

        @Test
        @DisplayName("Should be case-sensitive when comparing permissions")
        void shouldBeCaseSensitive() {
            var permission = new OperationPermission(Set.of("Admin"));
            assertFalse(permission.isAllowed(Set.of("admin")));
            assertFalse(permission.isAllowed(Set.of("ADMIN")));
            assertTrue(permission.isAllowed(Set.of("Admin")));
        }

        @Test
        @DisplayName("Should handle permissions with special characters")
        void shouldHandleSpecialCharacters() {
            var permission = new OperationPermission(Set.of("team:platform", "role.admin", "service-lead"));
            assertTrue(permission.isAllowed(Set.of("team:platform")));
            assertTrue(permission.isAllowed(Set.of("role.admin")));
            assertTrue(permission.isAllowed(Set.of("service-lead")));
            assertFalse(permission.isAllowed(Set.of("team-platform")));
        }

        @Test
        @DisplayName("Should handle whitespace-only permissions correctly")
        void shouldHandleWhitespaceOnlyPermissions() {
            var permission = new OperationPermission(Set.of(" ", "admin"));
            assertTrue(permission.isAllowed(Set.of(" ")));
            assertTrue(permission.isAllowed(Set.of("admin")));
            assertFalse(permission.isAllowed(Set.of("  ")));
        }
    }
}
