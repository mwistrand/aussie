package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Group;

@DisplayName("Group")
class GroupTest {

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("should reject null ID")
        void shouldRejectNullId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Group(null, "Display", null, Set.of(), Instant.now(), Instant.now()));
        }

        @Test
        @DisplayName("should reject blank ID")
        void shouldRejectBlankId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Group("   ", "Display", null, Set.of(), Instant.now(), Instant.now()));
        }

        @Test
        @DisplayName("should default displayName to id when null")
        void shouldDefaultDisplayNameToId() {
            final var group = new Group("test-group", null, null, Set.of(), null, null);
            assertEquals("test-group", group.displayName());
        }

        @Test
        @DisplayName("should default displayName to id when blank")
        void shouldDefaultDisplayNameToIdWhenBlank() {
            final var group = new Group("test-group", "   ", null, Set.of(), null, null);
            assertEquals("test-group", group.displayName());
        }

        @Test
        @DisplayName("should default description to empty string")
        void shouldDefaultDescriptionToEmptyString() {
            final var group = new Group("test-group", "Display", null, Set.of(), null, null);
            assertEquals("", group.description());
        }

        @Test
        @DisplayName("should default permissions to empty set")
        void shouldDefaultPermissionsToEmptySet() {
            final var group = new Group("test-group", "Display", null, null, null, null);
            assertTrue(group.permissions().isEmpty());
        }

        @Test
        @DisplayName("should make permissions immutable")
        void shouldMakePermissionsImmutable() {
            final var group = new Group("test-group", "Display", null, Set.of("perm1"), null, null);
            assertThrows(UnsupportedOperationException.class, () -> group.permissions()
                    .add("perm2"));
        }

        @Test
        @DisplayName("should default createdAt to now")
        void shouldDefaultCreatedAtToNow() {
            final var before = Instant.now();
            final var group = new Group("test-group", "Display", null, Set.of(), null, null);
            final var after = Instant.now();

            assertNotNull(group.createdAt());
            assertTrue(!group.createdAt().isBefore(before));
            assertTrue(!group.createdAt().isAfter(after));
        }

        @Test
        @DisplayName("should default updatedAt to createdAt")
        void shouldDefaultUpdatedAtToCreatedAt() {
            final var createdAt = Instant.now().minusSeconds(3600);
            final var group = new Group("test-group", "Display", null, Set.of(), createdAt, null);

            assertEquals(createdAt, group.updatedAt());
        }
    }

    @Nested
    @DisplayName("static factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("create() should set id, displayName, and permissions")
        void createShouldSetFields() {
            final var group = Group.create("platform-team", "Platform Team", Set.of("*"));

            assertEquals("platform-team", group.id());
            assertEquals("Platform Team", group.displayName());
            assertEquals(Set.of("*"), group.permissions());
            assertNotNull(group.createdAt());
            assertEquals(group.createdAt(), group.updatedAt());
        }

        @Test
        @DisplayName("builder should create group with all fields")
        void builderShouldCreateGroup() {
            final var createdAt = Instant.now().minusSeconds(3600);
            final var updatedAt = Instant.now();

            final var group = Group.builder("developers")
                    .displayName("Developers")
                    .description("All developers")
                    .permissions(Set.of("apikeys.read", "service.config.read"))
                    .createdAt(createdAt)
                    .updatedAt(updatedAt)
                    .build();

            assertEquals("developers", group.id());
            assertEquals("Developers", group.displayName());
            assertEquals("All developers", group.description());
            assertEquals(Set.of("apikeys.read", "service.config.read"), group.permissions());
            assertEquals(createdAt, group.createdAt());
            assertEquals(updatedAt, group.updatedAt());
        }
    }

    @Nested
    @DisplayName("mutation methods")
    class MutationMethodTests {

        @Test
        @DisplayName("withPermissions() should create copy with new permissions")
        void withPermissionsShouldCreateCopy() {
            final var original = Group.create("test", "Test", Set.of("perm1"));
            final var updated = original.withPermissions(Set.of("perm2", "perm3"));

            assertEquals(Set.of("perm1"), original.permissions());
            assertEquals(Set.of("perm2", "perm3"), updated.permissions());
            assertEquals(original.id(), updated.id());
            assertEquals(original.displayName(), updated.displayName());
            assertEquals(original.createdAt(), updated.createdAt());
            assertNotEquals(original.updatedAt(), updated.updatedAt());
        }

        @Test
        @DisplayName("withDetails() should create copy with new displayName and description")
        void withDetailsShouldCreateCopy() {
            final var original = Group.create("test", "Test", Set.of("perm1"));
            final var updated = original.withDetails("New Name", "New Description");

            assertEquals("Test", original.displayName());
            assertEquals("", original.description());
            assertEquals("New Name", updated.displayName());
            assertEquals("New Description", updated.description());
            assertEquals(original.permissions(), updated.permissions());
            assertNotEquals(original.updatedAt(), updated.updatedAt());
        }
    }
}
