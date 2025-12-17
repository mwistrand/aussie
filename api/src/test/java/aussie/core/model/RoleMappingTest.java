package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.RoleMapping;

@DisplayName("RoleMapping")
class RoleMappingTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should accept null and create empty mapping")
        void shouldAcceptNull() {
            final var mapping = new RoleMapping(null);
            assertEquals(0, mapping.size());
        }

        @Test
        @DisplayName("should create immutable copy of provided map")
        void shouldCreateImmutableCopy() {
            final var mutableMap = new java.util.HashMap<String, Set<String>>();
            mutableMap.put("role1", Set.of("perm1"));

            final var mapping = new RoleMapping(mutableMap);

            // Modify original map
            mutableMap.put("role2", Set.of("perm2"));

            // Mapping should not be affected
            assertFalse(mapping.hasRole("role2"));
        }
    }

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("should create empty mapping")
        void shouldCreateEmptyMapping() {
            final var mapping = RoleMapping.empty();
            assertEquals(0, mapping.size());
            assertTrue(mapping.expandRoles(Set.of("any")).isEmpty());
        }
    }

    @Nested
    @DisplayName("expandRoles()")
    class ExpandRolesTests {

        @Test
        @DisplayName("should return empty set for null roles")
        void shouldReturnEmptyForNullRoles() {
            final var mapping = new RoleMapping(Map.of("role1", Set.of("perm1")));
            assertTrue(mapping.expandRoles(null).isEmpty());
        }

        @Test
        @DisplayName("should return empty set for empty roles")
        void shouldReturnEmptyForEmptyRoles() {
            final var mapping = new RoleMapping(Map.of("role1", Set.of("perm1")));
            assertTrue(mapping.expandRoles(Set.of()).isEmpty());
        }

        @Test
        @DisplayName("should expand single role to its permissions")
        void shouldExpandSingleRole() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read", "service.config.read")));

            final var result = mapping.expandRoles(Set.of("developers"));

            assertEquals(Set.of("apikeys.read", "service.config.read"), result);
        }

        @Test
        @DisplayName("should expand multiple roles and combine permissions")
        void shouldExpandMultipleRoles() {
            final var mapping = new RoleMapping(Map.of(
                    "developers", Set.of("apikeys.read", "service.config.read"),
                    "admins", Set.of("apikeys.write", "service.config.write")));

            final var result = mapping.expandRoles(Set.of("developers", "admins"));

            assertEquals(
                    Set.of("apikeys.read", "apikeys.write", "service.config.read", "service.config.write"), result);
        }

        @Test
        @DisplayName("should handle overlapping permissions")
        void shouldHandleOverlappingPermissions() {
            final var mapping = new RoleMapping(Map.of(
                    "developers", Set.of("apikeys.read", "common"),
                    "admins", Set.of("apikeys.write", "common")));

            final var result = mapping.expandRoles(Set.of("developers", "admins"));

            // "common" should appear only once
            assertEquals(Set.of("apikeys.read", "apikeys.write", "common"), result);
        }

        @Test
        @DisplayName("should silently ignore unknown roles")
        void shouldIgnoreUnknownRoles() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.expandRoles(Set.of("developers", "unknown-role"));

            assertEquals(Set.of("apikeys.read"), result);
        }

        @Test
        @DisplayName("should return empty for all unknown roles")
        void shouldReturnEmptyForAllUnknownRoles() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.expandRoles(Set.of("unknown1", "unknown2"));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getEffectivePermissions()")
    class GetEffectivePermissionsTests {

        @Test
        @DisplayName("should combine role permissions with direct permissions")
        void shouldCombineRoleAndDirectPermissions() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), Set.of("custom.permission"));

            assertEquals(Set.of("apikeys.read", "custom.permission"), result);
        }

        @Test
        @DisplayName("should return only direct permissions when no roles")
        void shouldReturnOnlyDirectPermissionsWhenNoRoles() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of(), Set.of("custom.permission"));

            assertEquals(Set.of("custom.permission"), result);
        }

        @Test
        @DisplayName("should return only role permissions when no direct permissions")
        void shouldReturnOnlyRolePermissionsWhenNoDirectPermissions() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), null);

            assertEquals(Set.of("apikeys.read"), result);
        }

        @Test
        @DisplayName("should handle overlapping permissions")
        void shouldHandleOverlapping() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("apikeys.read", "overlap")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), Set.of("custom", "overlap"));

            assertEquals(Set.of("apikeys.read", "overlap", "custom"), result);
        }
    }

    @Nested
    @DisplayName("hasRole()")
    class HasRoleTests {

        @Test
        @DisplayName("should return true for existing role")
        void shouldReturnTrueForExistingRole() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("perm")));
            assertTrue(mapping.hasRole("developers"));
        }

        @Test
        @DisplayName("should return false for non-existing role")
        void shouldReturnFalseForNonExistingRole() {
            final var mapping = new RoleMapping(Map.of("developers", Set.of("perm")));
            assertFalse(mapping.hasRole("admins"));
        }
    }

    @Nested
    @DisplayName("size()")
    class SizeTests {

        @Test
        @DisplayName("should return correct count")
        void shouldReturnCorrectCount() {
            final var mapping = new RoleMapping(Map.of(
                    "role1", Set.of("perm1"),
                    "role2", Set.of("perm2"),
                    "role3", Set.of("perm3")));

            assertEquals(3, mapping.size());
        }
    }
}
