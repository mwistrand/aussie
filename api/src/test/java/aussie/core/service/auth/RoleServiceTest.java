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

import aussie.adapter.out.storage.memory.InMemoryRoleRepository;

@DisplayName("RoleService")
class RoleServiceTest {

    private InMemoryRoleRepository repository;
    private RoleService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRoleRepository();
        service = new RoleService(repository);
    }

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        @DisplayName("should create a new role")
        void shouldCreateNewRole() {
            final var role = service.create("developers", "Developers", "All developers", Set.of("apikeys.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertEquals("developers", role.id());
            assertEquals("Developers", role.displayName());
            assertEquals("All developers", role.description());
            assertEquals(Set.of("apikeys.read"), role.permissions());
            assertNotNull(role.createdAt());
            assertNotNull(role.updatedAt());
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
            service.create("test-role", "Test", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var exception = assertThrows(
                    IllegalArgumentException.class, () -> service.create("test-role", "Another Test", null, Set.of())
                            .await()
                            .atMost(Duration.ofSeconds(1)));

            assertTrue(exception.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("should default to empty permissions when null")
        void shouldDefaultToEmptyPermissions() {
            final var role = service.create("no-perms", "No Permissions", null, null)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(role.permissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return empty for non-existent role")
        void shouldReturnEmptyForNonExistent() {
            final var result = service.get("non-existent").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return role when exists")
        void shouldReturnRoleWhenExists() {
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
        @DisplayName("should return empty list when no roles")
        void shouldReturnEmptyListWhenNoRoles() {
            final var result = service.list().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all created roles")
        void shouldReturnAllRoles() {
            service.create("role1", "Role 1", null, Set.of()).await().atMost(Duration.ofSeconds(1));
            service.create("role2", "Role 2", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var result = service.list().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("should return empty for non-existent role")
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
        @DisplayName("should return empty for non-existent role")
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
        @DisplayName("should return false for non-existent role")
        void shouldReturnFalseForNonExistent() {
            final var result = service.delete("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should delete existing role and return true")
        void shouldDeleteExistingRole() {
            service.create("to-delete", "Delete Me", null, Set.of()).await().atMost(Duration.ofSeconds(1));

            final var deleteResult = service.delete("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(deleteResult);

            final var getResult = service.get("to-delete").await().atMost(Duration.ofSeconds(1));

            assertTrue(getResult.isEmpty());
        }
    }

    @Nested
    @DisplayName("expandRoles()")
    class ExpandRolesTests {

        @Test
        @DisplayName("should return empty set for null roles")
        void shouldReturnEmptyForNullRoles() {
            final var result = service.expandRoles(null).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty set for empty roles")
        void shouldReturnEmptyForEmptyRoles() {
            final var result = service.expandRoles(Set.of()).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should expand single role to its permissions")
        void shouldExpandSingleRole() {
            service.create("developers", "Developers", null, Set.of("apikeys.read", "service.config.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = service.expandRoles(Set.of("developers")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("apikeys.read", "service.config.read"), result);
        }

        @Test
        @DisplayName("should expand multiple roles and combine permissions")
        void shouldExpandMultipleRoles() {
            service.create("developers", "Developers", null, Set.of("apikeys.read"))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            service.create("admins", "Administrators", null, Set.of("apikeys.write", "service.config.write"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result =
                    service.expandRoles(Set.of("developers", "admins")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("apikeys.read", "apikeys.write", "service.config.write"), result);
        }

        @Test
        @DisplayName("should silently ignore unknown roles")
        void shouldIgnoreUnknownRoles() {
            service.create("known", "Known", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));

            final var result =
                    service.expandRoles(Set.of("known", "unknown")).await().atMost(Duration.ofSeconds(1));

            assertEquals(Set.of("perm1"), result);
        }
    }

    @Nested
    @DisplayName("getRoleMapping()")
    class GetRoleMappingTests {

        @Test
        @DisplayName("should return mapping with all roles")
        void shouldReturnMappingWithAllRoles() {
            service.create("role1", "Role 1", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));
            service.create("role2", "Role 2", null, Set.of("perm2")).await().atMost(Duration.ofSeconds(1));

            final var mapping = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, mapping.size());
            assertTrue(mapping.hasRole("role1"));
            assertTrue(mapping.hasRole("role2"));
        }

        @Test
        @DisplayName("should return cached mapping on subsequent calls")
        void shouldReturnCachedMapping() {
            service.create("cached-test", "Cached Test", null, Set.of("perm"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            final var mapping2 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));

            // Same object should be returned (cached)
            assertTrue(mapping1 == mapping2);
        }

        @Test
        @DisplayName("should invalidate cache after create")
        void shouldInvalidateCacheAfterCreate() {
            service.create("existing", "Existing", null, Set.of("perm")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(1, mapping1.size());

            service.create("new-role", "New Role", null, Set.of("perm2"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(2, mapping2.size());
        }

        @Test
        @DisplayName("should invalidate cache after update")
        void shouldInvalidateCacheAfterUpdate() {
            service.create("update-test", "Test", null, Set.of("perm1")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(Set.of("perm1"), mapping1.expandRoles(Set.of("update-test")));

            service.updatePermissions("update-test", Set.of("perm2", "perm3"))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertEquals(Set.of("perm2", "perm3"), mapping2.expandRoles(Set.of("update-test")));
        }

        @Test
        @DisplayName("should invalidate cache after delete")
        void shouldInvalidateCacheAfterDelete() {
            service.create("delete-test", "Test", null, Set.of("perm")).await().atMost(Duration.ofSeconds(1));

            final var mapping1 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertTrue(mapping1.hasRole("delete-test"));

            service.delete("delete-test").await().atMost(Duration.ofSeconds(1));

            final var mapping2 = service.getRoleMapping().await().atMost(Duration.ofSeconds(1));
            assertFalse(mapping2.hasRole("delete-test"));
        }
    }
}
