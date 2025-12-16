package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Group;

@DisplayName("InMemoryGroupRepository")
class InMemoryGroupRepositoryTest {

    private InMemoryGroupRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryGroupRepository();
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("should save a new group")
        void shouldSaveNewGroup() {
            final var group = Group.create("developers", "Developers", Set.of("apikeys.read"));

            repository.save(group).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("developers").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("developers", result.get().id());
            assertEquals("Developers", result.get().displayName());
            assertEquals(Set.of("apikeys.read"), result.get().permissions());
        }

        @Test
        @DisplayName("should overwrite existing group with same ID")
        void shouldOverwriteExistingGroup() {
            final var original = Group.create("developers", "Developers", Set.of("apikeys.read"));
            repository.save(original).await().atMost(Duration.ofSeconds(1));

            final var updated = original.withPermissions(Set.of("apikeys.write", "service.config.read"));
            repository.save(updated).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("developers").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals(
                    Set.of("apikeys.write", "service.config.read"), result.get().permissions());
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindByIdTests {

        @Test
        @DisplayName("should return empty for non-existent group")
        void shouldReturnEmptyForNonExistent() {
            final var result = repository.findById("non-existent").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return group when exists")
        void shouldReturnGroupWhenExists() {
            final var group = Group.create("admins", "Administrators", Set.of("*"));
            repository.save(group).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("admins").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("admins", result.get().id());
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("should return false when deleting non-existent group")
        void shouldReturnFalseForNonExistent() {
            final var result = repository.delete("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true and remove existing group")
        void shouldRemoveExistingGroup() {
            final var group = Group.create("to-delete", "To Delete", Set.of());
            repository.save(group).await().atMost(Duration.ofSeconds(1));

            final var deleteResult = repository.delete("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(deleteResult);

            final var findResult = repository.findById("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(findResult.isEmpty());
        }
    }

    @Nested
    @DisplayName("findAll()")
    class FindAllTests {

        @Test
        @DisplayName("should return empty list when no groups")
        void shouldReturnEmptyListWhenNoGroups() {
            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all saved groups")
        void shouldReturnAllGroups() {
            repository
                    .save(Group.create("group1", "Group 1", Set.of("perm1")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Group.create("group2", "Group 2", Set.of("perm2")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Group.create("group3", "Group 3", Set.of("perm3")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, result.size());
        }
    }

    @Nested
    @DisplayName("exists()")
    class ExistsTests {

        @Test
        @DisplayName("should return false for non-existent group")
        void shouldReturnFalseForNonExistent() {
            final var result = repository.exists("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true for existing group")
        void shouldReturnTrueForExisting() {
            repository
                    .save(Group.create("exists-test", "Test", Set.of()))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.exists("exists-test").await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("getGroupMapping()")
    class GetGroupMappingTests {

        @Test
        @DisplayName("should return empty mapping when no groups")
        void shouldReturnEmptyMappingWhenNoGroups() {
            final var result = repository.getGroupMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(0, result.size());
        }

        @Test
        @DisplayName("should return mapping with all groups")
        void shouldReturnMappingWithAllGroups() {
            repository
                    .save(Group.create("developers", "Developers", Set.of("apikeys.read", "service.config.read")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Group.create("admins", "Administrators", Set.of("*")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.getGroupMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
            assertTrue(result.hasGroup("developers"));
            assertTrue(result.hasGroup("admins"));

            final var expanded = result.expandGroups(Set.of("developers"));
            assertEquals(Set.of("apikeys.read", "service.config.read"), expanded);
        }

        @Test
        @DisplayName("should reflect changes after save")
        void shouldReflectChangesAfterSave() {
            repository
                    .save(Group.create("group1", "Group 1", Set.of("perm1")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping1 = repository.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(1, mapping1.size());

            repository
                    .save(Group.create("group2", "Group 2", Set.of("perm2")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = repository.getGroupMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(2, mapping2.size());
        }
    }

    @Nested
    @DisplayName("concurrent operations")
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("should handle concurrent saves safely")
        void shouldHandleConcurrentSaves() {
            // Save multiple groups concurrently using Uni.join
            io.smallrye.mutiny.Uni.join()
                    .all(
                            repository.save(Group.create("concurrent1", "Concurrent 1", Set.of("perm1"))),
                            repository.save(Group.create("concurrent2", "Concurrent 2", Set.of("perm2"))),
                            repository.save(Group.create("concurrent3", "Concurrent 3", Set.of("perm3"))))
                    .andFailFast()
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, result.size());
        }
    }
}
