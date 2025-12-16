package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.GroupMapping;

@DisplayName("GroupMapping")
class GroupMappingTest {

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should accept null and create empty mapping")
        void shouldAcceptNull() {
            final var mapping = new GroupMapping(null);
            assertEquals(0, mapping.size());
        }

        @Test
        @DisplayName("should create immutable copy of provided map")
        void shouldCreateImmutableCopy() {
            final var mutableMap = new java.util.HashMap<String, Set<String>>();
            mutableMap.put("group1", Set.of("perm1"));

            final var mapping = new GroupMapping(mutableMap);

            // Modify original map
            mutableMap.put("group2", Set.of("perm2"));

            // Mapping should not be affected
            assertFalse(mapping.hasGroup("group2"));
        }
    }

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("should create empty mapping")
        void shouldCreateEmptyMapping() {
            final var mapping = GroupMapping.empty();
            assertEquals(0, mapping.size());
            assertTrue(mapping.expandGroups(Set.of("any")).isEmpty());
        }
    }

    @Nested
    @DisplayName("expandGroups()")
    class ExpandGroupsTests {

        @Test
        @DisplayName("should return empty set for null groups")
        void shouldReturnEmptyForNullGroups() {
            final var mapping = new GroupMapping(Map.of("group1", Set.of("perm1")));
            assertTrue(mapping.expandGroups(null).isEmpty());
        }

        @Test
        @DisplayName("should return empty set for empty groups")
        void shouldReturnEmptyForEmptyGroups() {
            final var mapping = new GroupMapping(Map.of("group1", Set.of("perm1")));
            assertTrue(mapping.expandGroups(Set.of()).isEmpty());
        }

        @Test
        @DisplayName("should expand single group to its permissions")
        void shouldExpandSingleGroup() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read", "service.config.read")));

            final var result = mapping.expandGroups(Set.of("developers"));

            assertEquals(Set.of("apikeys.read", "service.config.read"), result);
        }

        @Test
        @DisplayName("should expand multiple groups and combine permissions")
        void shouldExpandMultipleGroups() {
            final var mapping = new GroupMapping(Map.of(
                    "developers", Set.of("apikeys.read", "service.config.read"),
                    "admins", Set.of("apikeys.write", "service.config.write")));

            final var result = mapping.expandGroups(Set.of("developers", "admins"));

            assertEquals(
                    Set.of("apikeys.read", "apikeys.write", "service.config.read", "service.config.write"), result);
        }

        @Test
        @DisplayName("should handle overlapping permissions")
        void shouldHandleOverlappingPermissions() {
            final var mapping = new GroupMapping(Map.of(
                    "developers", Set.of("apikeys.read", "common"),
                    "admins", Set.of("apikeys.write", "common")));

            final var result = mapping.expandGroups(Set.of("developers", "admins"));

            // "common" should appear only once
            assertEquals(Set.of("apikeys.read", "apikeys.write", "common"), result);
        }

        @Test
        @DisplayName("should silently ignore unknown groups")
        void shouldIgnoreUnknownGroups() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.expandGroups(Set.of("developers", "unknown-group"));

            assertEquals(Set.of("apikeys.read"), result);
        }

        @Test
        @DisplayName("should return empty for all unknown groups")
        void shouldReturnEmptyForAllUnknownGroups() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.expandGroups(Set.of("unknown1", "unknown2"));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getEffectivePermissions()")
    class GetEffectivePermissionsTests {

        @Test
        @DisplayName("should combine group permissions with direct permissions")
        void shouldCombineGroupAndDirectPermissions() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), Set.of("custom.permission"));

            assertEquals(Set.of("apikeys.read", "custom.permission"), result);
        }

        @Test
        @DisplayName("should return only direct permissions when no groups")
        void shouldReturnOnlyDirectPermissionsWhenNoGroups() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of(), Set.of("custom.permission"));

            assertEquals(Set.of("custom.permission"), result);
        }

        @Test
        @DisplayName("should return only group permissions when no direct permissions")
        void shouldReturnOnlyGroupPermissionsWhenNoDirectPermissions() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), null);

            assertEquals(Set.of("apikeys.read"), result);
        }

        @Test
        @DisplayName("should handle overlapping permissions")
        void shouldHandleOverlapping() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("apikeys.read", "overlap")));

            final var result = mapping.getEffectivePermissions(Set.of("developers"), Set.of("custom", "overlap"));

            assertEquals(Set.of("apikeys.read", "overlap", "custom"), result);
        }
    }

    @Nested
    @DisplayName("hasGroup()")
    class HasGroupTests {

        @Test
        @DisplayName("should return true for existing group")
        void shouldReturnTrueForExistingGroup() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("perm")));
            assertTrue(mapping.hasGroup("developers"));
        }

        @Test
        @DisplayName("should return false for non-existing group")
        void shouldReturnFalseForNonExistingGroup() {
            final var mapping = new GroupMapping(Map.of("developers", Set.of("perm")));
            assertFalse(mapping.hasGroup("admins"));
        }
    }

    @Nested
    @DisplayName("size()")
    class SizeTests {

        @Test
        @DisplayName("should return correct count")
        void shouldReturnCorrectCount() {
            final var mapping = new GroupMapping(Map.of(
                    "group1", Set.of("perm1"),
                    "group2", Set.of("perm2"),
                    "group3", Set.of("perm3")));

            assertEquals(3, mapping.size());
        }
    }
}
