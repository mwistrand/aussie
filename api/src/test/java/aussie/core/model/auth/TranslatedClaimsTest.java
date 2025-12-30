package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TranslatedClaims.
 */
@DisplayName("TranslatedClaims")
class TranslatedClaimsTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            var roles = Set.of("admin", "user");
            var permissions = Set.of("read", "write");
            var attributes = Map.<String, Object>of("tenant", "acme");

            var claims = new TranslatedClaims(roles, permissions, attributes);

            assertEquals(roles, claims.roles());
            assertEquals(permissions, claims.permissions());
            assertEquals(attributes, claims.attributes());
        }

        @Test
        @DisplayName("should default null roles to empty set")
        void shouldDefaultNullRoles() {
            var claims = new TranslatedClaims(null, Set.of(), Map.of());

            assertNotNull(claims.roles());
            assertTrue(claims.roles().isEmpty());
        }

        @Test
        @DisplayName("should default null permissions to empty set")
        void shouldDefaultNullPermissions() {
            var claims = new TranslatedClaims(Set.of(), null, Map.of());

            assertNotNull(claims.permissions());
            assertTrue(claims.permissions().isEmpty());
        }

        @Test
        @DisplayName("should default null attributes to empty map")
        void shouldDefaultNullAttributes() {
            var claims = new TranslatedClaims(Set.of(), Set.of(), null);

            assertNotNull(claims.attributes());
            assertTrue(claims.attributes().isEmpty());
        }
    }

    @Nested
    @DisplayName("empty factory")
    class EmptyFactory {

        @Test
        @DisplayName("should create empty instance")
        void shouldCreateEmptyInstance() {
            var claims = TranslatedClaims.empty();

            assertNotNull(claims);
            assertTrue(claims.roles().isEmpty());
            assertTrue(claims.permissions().isEmpty());
            assertTrue(claims.attributes().isEmpty());
        }

        @Test
        @DisplayName("empty instance should report isEmpty as true")
        void shouldReportIsEmpty() {
            var claims = TranslatedClaims.empty();

            assertTrue(claims.isEmpty());
        }
    }

    @Nested
    @DisplayName("merge")
    class Merge {

        @Test
        @DisplayName("should merge roles from both instances")
        void shouldMergeRoles() {
            var claims1 = new TranslatedClaims(Set.of("admin"), Set.of(), Map.of());
            var claims2 = new TranslatedClaims(Set.of("user"), Set.of(), Map.of());

            var merged = claims1.merge(claims2);

            assertEquals(Set.of("admin", "user"), merged.roles());
        }

        @Test
        @DisplayName("should merge permissions from both instances")
        void shouldMergePermissions() {
            var claims1 = new TranslatedClaims(Set.of(), Set.of("read"), Map.of());
            var claims2 = new TranslatedClaims(Set.of(), Set.of("write"), Map.of());

            var merged = claims1.merge(claims2);

            assertEquals(Set.of("read", "write"), merged.permissions());
        }

        @Test
        @DisplayName("should merge attributes with other overwriting")
        void shouldMergeAttributes() {
            var claims1 = new TranslatedClaims(Set.of(), Set.of(), Map.of("key1", "value1", "shared", "first"));
            var claims2 = new TranslatedClaims(Set.of(), Set.of(), Map.of("key2", "value2", "shared", "second"));

            var merged = claims1.merge(claims2);

            assertEquals("value1", merged.attributes().get("key1"));
            assertEquals("value2", merged.attributes().get("key2"));
            assertEquals("second", merged.attributes().get("shared")); // Other overwrites
        }

        @Test
        @DisplayName("should return this when merging with null")
        void shouldReturnThisWhenMergingWithNull() {
            var claims = new TranslatedClaims(Set.of("admin"), Set.of("read"), Map.of("key", "value"));

            var merged = claims.merge(null);

            assertEquals(claims, merged);
        }

        @Test
        @DisplayName("should handle merging empty instances")
        void shouldHandleMergingEmptyInstances() {
            var claims1 = TranslatedClaims.empty();
            var claims2 = TranslatedClaims.empty();

            var merged = claims1.merge(claims2);

            assertTrue(merged.isEmpty());
        }
    }

    @Nested
    @DisplayName("helper methods")
    class HelperMethods {

        @Test
        @DisplayName("hasRoles should return true when roles present")
        void hasRolesShouldReturnTrueWhenPresent() {
            var claims = new TranslatedClaims(Set.of("admin"), Set.of(), Map.of());

            assertTrue(claims.hasRoles());
        }

        @Test
        @DisplayName("hasRoles should return false when roles empty")
        void hasRolesShouldReturnFalseWhenEmpty() {
            var claims = new TranslatedClaims(Set.of(), Set.of("read"), Map.of());

            assertFalse(claims.hasRoles());
        }

        @Test
        @DisplayName("hasPermissions should return true when permissions present")
        void hasPermissionsShouldReturnTrueWhenPresent() {
            var claims = new TranslatedClaims(Set.of(), Set.of("read"), Map.of());

            assertTrue(claims.hasPermissions());
        }

        @Test
        @DisplayName("hasPermissions should return false when permissions empty")
        void hasPermissionsShouldReturnFalseWhenEmpty() {
            var claims = new TranslatedClaims(Set.of("admin"), Set.of(), Map.of());

            assertFalse(claims.hasPermissions());
        }

        @Test
        @DisplayName("isEmpty should return false when has roles")
        void isEmptyShouldReturnFalseWhenHasRoles() {
            var claims = new TranslatedClaims(Set.of("admin"), Set.of(), Map.of());

            assertFalse(claims.isEmpty());
        }

        @Test
        @DisplayName("isEmpty should return false when has permissions")
        void isEmptyShouldReturnFalseWhenHasPermissions() {
            var claims = new TranslatedClaims(Set.of(), Set.of("read"), Map.of());

            assertFalse(claims.isEmpty());
        }

        @Test
        @DisplayName("isEmpty should return false when has attributes")
        void isEmptyShouldReturnFalseWhenHasAttributes() {
            var claims = new TranslatedClaims(Set.of(), Set.of(), Map.of("key", "value"));

            assertFalse(claims.isEmpty());
        }
    }
}
