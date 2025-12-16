package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.memory.InMemoryGroupRepository;

@DisplayName("GroupService")
class GroupServiceTest {

    private InMemoryGroupRepository repository;
    private GroupService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGroupRepository();
        service = new GroupService(repository);
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("should create a new group")
        void shouldCreateNewGroup() {
            final var group = service.create("developers", "Developers", "All developers", Set.of("apikeys.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertEquals("developers", group.id());
            assertEquals("Developers", group.displayName());
            assertEquals("All developers", group.description());
            assertEquals(Set.of("apikeys.read"), group.permissions());
            assertNotNull(group.createdAt());
            assertNotNull(group.updatedAt());
        }

        @Test
        @DisplayName("should reject null ID")
        void shouldRejectNullId() {
            final var exception =
                    assertThrows(IllegalArgumentException.class, () -> service.create(null, "Display", null, Set.of())
                            .await()
                            .atMost(Duration.ofSeconds(1)));

            assertTrue(exception.getMessage().contains("null or blank"));
        }

        @Test
        @DisplayName("should reject blank ID")
        void shouldRejectBlankId() {
            assertThrows(IllegalArgumentException.class, () -> service.create("   ", "Display", null, Set.of())
                    .await()
                    .atMost(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("should reject duplicate ID")
        void shouldRejectDuplicateId() {
            service.create("test-group", "Test", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var exception = assertThrows(
                    IllegalArgumentException.class, () -> service.create("test-group", "Another Test", null, Set.of())
                            .await()
                            .atMost(Duration.ofSeconds(1)));

            assertTrue(exception.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("should default to empty permissions when null")
        void shouldDefaultToEmptyPermissions() {
            final var group = service.create("no-perms", "No Permissions", null, null)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(group.permissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return empty for non-existent group")
        void shouldReturnEmptyForNonExistent() {
            final var result = service.get("non-existent").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return group when exists")
        void shouldReturnGroupWhenExists() {
            service.create("test", "Test", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));

            final var result = service.get("test").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("test", result.get().id());
        }
    }

    @Nested
    @DisplayName("list()")
    class ListTests {

        @Test
        @DisplayName("should return empty list when no groups")
        void shouldReturnEmptyListWhenNoGroups() {
            final var result = service.list().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all created groups")
        void shouldReturnAllGroups() {
            service.create("group1", "Group 1", null, Set.of()).await().atMost(Duration.ofSeconds(1));
            service.create("group2", "Group 2", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var result = service.list().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("should return empty for non-existent group")
        void shouldReturnEmptyForNonExistent() {
            final var result = service.update("non-existent", "New Name", "New Desc", Set.of())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should update all fields when provided")
        void shouldUpdateAllFields() {
            service.create("to-update", "Original", "Original desc", Set.of("perm1"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = service.update("to-update", "Updated", "Updated desc", Set.of("perm2", "perm3"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("Updated", result.get().displayName());
            assertEquals("Updated desc", result.get().description());
            assertEquals(Set.of("perm2", "perm3"), result.get().permissions());
        }

        @Test
        @DisplayName("should preserve existing values when null provided")
        void shouldPreserveExistingValuesWhenNullProvided() {
            service.create("preserve", "Original Name", "Original Desc", Set.of("perm1"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result =
                    service.update("preserve", null, null, null).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("Original Name", result.get().displayName());
            assertEquals("Original Desc", result.get().description());
            assertEquals(Set.of("perm1"), result.get().permissions());
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateTimestamp() {
            final var original = service.create("timestamp-test", "Test", null, Set.of())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            // Small delay to ensure different timestamp
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final var updated = service.update("timestamp-test", "Updated", null, null)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(updated.isPresent());
            assertTrue(updated.get().updatedAt().isAfter(original.updatedAt())
                    || updated.get().updatedAt().equals(original.updatedAt()));
        }
    }

    @Nested
    @DisplayName("updatePermissions()")
    class UpdatePermissionsTests {

        @Test
        @DisplayName("should return empty for non-existent group")
        void shouldReturnEmptyForNonExistent() {
            final var result = service.updatePermissions("non-existent", Set.of("perm"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should update only permissions")
        void shouldUpdateOnlyPermissions() {
            service.create("perms-test", "Original Name", "Original Desc", Set.of("perm1"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = service.updatePermissions("perms-test", Set.of("newperm1", "newperm2"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("Original Name", result.get().displayName());
            assertEquals("Original Desc", result.get().description());
            assertEquals(Set.of("newperm1", "newperm2"), result.get().permissions());
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("should return false for non-existent group")
        void shouldReturnFalseForNonExistent() {
            final var result = service.delete("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should delete existing group and return true")
        void shouldDeleteExistingGroup() {
            service.create("to-delete", "Delete Me", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var deleteResult = service.delete("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(deleteResult);

            final var getResult = service.get("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(getResult.isEmpty());
        }
    }

    @Nested
    @DisplayName("expandGroups()")
    class ExpandGroupsTests {

        @Test
        @DisplayName("should return empty set for null groups")
        void shouldReturnEmptyForNullGroups() {
            final var result = service.expandGroups(null).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty set for empty groups")
        void shouldReturnEmptyForEmptyGroups() {
            final var result = service.expandGroups(Set.of()).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should expand single group to its permissions")
        void shouldExpandSingleGroup() {
            service.create("developers", "Developers", null, Set.of("apikeys.read", "service.config.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result =
                    service.expandGroups(Set.of("developers")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("apikeys.read", "service.config.read"), result);
        }

        @Test
        @DisplayName("should expand multiple groups and combine permissions")
        void shouldExpandMultipleGroups() {
            service.create("developers", "Developers", null, Set.of("apikeys.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            service.create("admins", "Administrators", null, Set.of("apikeys.write", "service.config.write"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result =
                    service.expandGroups(Set.of("developers", "admins")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("apikeys.read", "apikeys.write", "service.config.write"), result);
        }

        @Test
        @DisplayName("should silently ignore unknown groups")
        void shouldIgnoreUnknownGroups() {
            service.create("known", "Known", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));

            final var result =
                    service.expandGroups(Set.of("known", "unknown")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("perm1"), result);
        }
    }

    @Nested
    @DisplayName("getGroupMapping()")
    class GetGroupMappingTests {

        @Test
        @DisplayName("should return mapping with all groups")
        void shouldReturnMappingWithAllGroups() {
            service.create("group1", "Group 1", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));
            service.create("group2", "Group 2", null, Set.of("perm2")).await().atMost(Duration.ofSeconds(1));

            final var mapping = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, mapping.size());
            assertTrue(mapping.hasGroup("group1"));
            assertTrue(mapping.hasGroup("group2"));
        }

        @Test
        @DisplayName("should return cached mapping on subsequent calls")
        void shouldReturnCachedMapping() {
            service.create("cached-test", "Cached Test", null, Set.of("perm"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            final var mapping2 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));

            // Same object should be returned (cached)
            assertTrue(mapping1 == mapping2);
        }

        @Test
        @DisplayName("should invalidate cache after create")
        void shouldInvalidateCacheAfterCreate() {
            service.create("existing", "Existing", null, Set.of("perm")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(1, mapping1.size());

            service.create("new-group", "New Group", null, Set.of("perm2"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(2, mapping2.size());
        }

        @Test
        @DisplayName("should invalidate cache after update")
        void shouldInvalidateCacheAfterUpdate() {
            service.create("update-test", "Test", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(Set.of("perm1"), mapping1.expandGroups(Set.of("update-test")));

            service.updatePermissions("update-test", Set.of("perm2", "perm3"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(Set.of("perm2", "perm3"), mapping2.expandGroups(Set.of("update-test")));
        }

        @Test
        @DisplayName("should invalidate cache after delete")
        void shouldInvalidateCacheAfterDelete() {
            service.create("delete-test", "Test", null, Set.of("perm")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertTrue(mapping1.hasGroup("delete-test"));

            service.delete("delete-test").await().atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertFalse(mapping2.hasGroup("delete-test"));
        }
    }
}
