package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Permission;

@DisplayName("Permission")
class PermissionTest {

    @Nested
    @DisplayName("toRoles")
    class ToRolesTests {

        @Test
        @DisplayName("Should return empty set for null input")
        void shouldReturnEmptyForNull() {
            assertEquals(Set.of(), Permission.toRoles(null));
        }

        @Test
        @DisplayName("Should return empty set for empty input")
        void shouldReturnEmptyForEmpty() {
            assertEquals(Set.of(), Permission.toRoles(Set.of()));
        }

        @Test
        @DisplayName("Should expand wildcard to all permission values")
        void shouldExpandWildcard() {
            var roles = Permission.toRoles(Set.of("*"));

            assertTrue(roles.contains("admin"));
            assertTrue(roles.contains("service.config.read"));
            assertTrue(roles.contains("service.config.create"));
            assertTrue(roles.contains("service.config.update"));
            assertTrue(roles.contains("service.config.delete"));
            assertTrue(roles.contains("service.permissions.read"));
            assertTrue(roles.contains("service.permissions.write"));
            assertTrue(roles.contains("aussie:admin"));
        }

        @Test
        @DisplayName("Should pass through plain permission without adding extras")
        void shouldPassThroughPlainPermission() {
            var roles = Permission.toRoles(Set.of("aussie:admin"));

            assertEquals(Set.of("aussie:admin"), roles);
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped config.read")
        void shouldAddUnscopedForConfigRead() {
            var roles = Permission.toRoles(Set.of("demo-service.config.read"));

            assertTrue(roles.contains("demo-service.config.read"));
            assertTrue(roles.contains("service.config.read"));
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped config.create")
        void shouldAddUnscopedForConfigCreate() {
            var roles = Permission.toRoles(Set.of("demo-service.config.create"));

            assertTrue(roles.contains("demo-service.config.create"));
            assertTrue(roles.contains("service.config.create"));
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped config.update")
        void shouldAddUnscopedForConfigUpdate() {
            var roles = Permission.toRoles(Set.of("demo-service.config.update"));

            assertTrue(roles.contains("demo-service.config.update"));
            assertTrue(roles.contains("service.config.update"));
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped config.delete")
        void shouldAddUnscopedForConfigDelete() {
            var roles = Permission.toRoles(Set.of("demo-service.config.delete"));

            assertTrue(roles.contains("demo-service.config.delete"));
            assertTrue(roles.contains("service.config.delete"));
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped permissions.read")
        void shouldAddUnscopedForPermissionsRead() {
            var roles = Permission.toRoles(Set.of("demo-service.permissions.read"));

            assertTrue(roles.contains("demo-service.permissions.read"));
            assertTrue(roles.contains("service.permissions.read"));
        }

        @Test
        @DisplayName("Should add un-scoped equivalent for service-scoped permissions.write")
        void shouldAddUnscopedForPermissionsWrite() {
            var roles = Permission.toRoles(Set.of("demo-service.permissions.write"));

            assertTrue(roles.contains("demo-service.permissions.write"));
            assertTrue(roles.contains("service.permissions.write"));
        }

        @Test
        @DisplayName("Should not duplicate already-unscoped permission")
        void shouldNotDuplicateAlreadyUnscoped() {
            var roles = Permission.toRoles(Set.of("service.config.update"));

            assertEquals(Set.of("service.config.update"), roles);
        }

        @Test
        @DisplayName("Should not add un-scoped equivalent for unrecognized suffix")
        void shouldNotAddUnscopedForUnrecognizedSuffix() {
            var roles = Permission.toRoles(Set.of("demo-service.config.execute"));

            assertEquals(Set.of("demo-service.config.execute"), roles);
        }

        @Test
        @DisplayName("Should handle mixed permissions correctly")
        void shouldHandleMixedPermissions() {
            var roles = Permission.toRoles(Set.of("aussie:admin", "demo-service.config.update", "other-claim"));

            assertTrue(roles.contains("aussie:admin"));
            assertTrue(roles.contains("demo-service.config.update"));
            assertTrue(roles.contains("service.config.update"));
            assertTrue(roles.contains("other-claim"));
            assertEquals(4, roles.size());
        }

        @Test
        @DisplayName("Should handle suffix-only string without error")
        void shouldHandleSuffixOnlyString() {
            var roles = Permission.toRoles(Set.of(".config.create"));

            // ".config.create" ends with ".config.create" and does not equal "service.config.create",
            // so it maps to the un-scoped equivalent
            assertTrue(roles.contains(".config.create"));
            assertTrue(roles.contains("service.config.create"));
        }
    }
}
