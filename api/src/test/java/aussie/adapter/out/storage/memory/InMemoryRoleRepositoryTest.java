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

import aussie.core.model.auth.Role;

@DisplayName("InMemoryRoleRepository")
class InMemoryRoleRepositoryTest {

    private InMemoryRoleRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRoleRepository();
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("should save a new role")
        void shouldSaveNewRole() {
            final var role = Role.create("developers", "Developers", Set.of("apikeys.read"));

            repository.save(role).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("developers").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("developers", result.get().id());
            assertEquals("Developers", result.get().displayName());
            assertEquals(Set.of("apikeys.read"), result.get().permissions());
        }

        @Test
        @DisplayName("should overwrite existing role with same ID")
        void shouldOverwriteExistingRole() {
            final var original = Role.create("developers", "Developers", Set.of("apikeys.read"));
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
        @DisplayName("should return empty for non-existent role")
        void shouldReturnEmptyForNonExistent() {
            final var result = repository.findById("non-existent").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return role when exists")
        void shouldReturnRoleWhenExists() {
            final var role = Role.create("admins", "Administrators", Set.of("*"));
            repository.save(role).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("admins").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("admins", result.get().id());
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("should return false when deleting non-existent role")
        void shouldReturnFalseForNonExistent() {
            final var result = repository.delete("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true and remove existing role")
        void shouldRemoveExistingRole() {
            final var role = Role.create("to-delete", "To Delete", Set.of());
            repository.save(role).await().atMost(Duration.ofSeconds(1));

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
        @DisplayName("should return empty list when no roles")
        void shouldReturnEmptyListWhenNoRoles() {
            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all saved roles")
        void shouldReturnAllRoles() {
            repository
                    .save(Role.create("role1", "Role 1", Set.of("perm1")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Role.create("role2", "Role 2", Set.of("perm2")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Role.create("role3", "Role 3", Set.of("perm3")))
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
        @DisplayName("should return false for non-existent role")
        void shouldReturnFalseForNonExistent() {
            final var result = repository.exists("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return true for existing role")
        void shouldReturnTrueForExisting() {
            repository
                    .save(Role.create("exists-test", "Test", Set.of()))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.exists("exists-test").await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("getRoleMapping()")
    class GetRoleMappingTests {

        @Test
        @DisplayName("should return empty mapping when no roles")
        void shouldReturnEmptyMappingWhenNoRoles() {
            final var result = repository.getRoleMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(0, result.size());
        }

        @Test
        @DisplayName("should return mapping with all roles")
        void shouldReturnMappingWithAllRoles() {
            repository
                    .save(Role.create("developers", "Developers", Set.of("apikeys.read", "service.config.read")))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .save(Role.create("admins", "Administrators", Set.of("*")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.getRoleMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
            assertTrue(result.hasRole("developers"));
            assertTrue(result.hasRole("admins"));

            final var expanded = result.expandRoles(Set.of("developers"));
            assertEquals(Set.of("apikeys.read", "service.config.read"), expanded);
        }

        @Test
        @DisplayName("should reflect changes after save")
        void shouldReflectChangesAfterSave() {
            repository
                    .save(Role.create("role1", "Role 1", Set.of("perm1")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping1 = repository.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(1, mapping1.size());

            repository
                    .save(Role.create("role2", "Role 2", Set.of("perm2")))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = repository.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(2, mapping2.size());
        }
    }

    @Nested
    @DisplayName("concurrent operations")
    class ConcurrentOperationsTests {

        @Test
        @DisplayName("should handle concurrent saves safely")
        void shouldHandleConcurrentSaves() {
            // Save multiple roles concurrently using Uni.join
            io.smallrye.mutiny.Uni.join()
                    .all(
                            repository.save(Role.create("concurrent1", "Concurrent 1", Set.of("perm1"))),
                            repository.save(Role.create("concurrent2", "Concurrent 2", Set.of("perm2"))),
                            repository.save(Role.create("concurrent3", "Concurrent 3", Set.of("perm3"))))
                    .andFailFast()
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, result.size());
        }
    }
}
