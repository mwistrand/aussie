package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.TranslationConfigSchema.ClaimSource;
import aussie.core.model.auth.TranslationConfigSchema.ClaimSource.ClaimType;
import aussie.core.model.auth.TranslationConfigSchema.Defaults;
import aussie.core.model.auth.TranslationConfigSchema.Mappings;
import aussie.core.model.auth.TranslationConfigSchema.Operation;
import aussie.core.model.auth.TranslationConfigSchema.Transform;

@DisplayName("ClaimTranslator")
class ClaimTranslatorTest {

    @Nested
    @DisplayName("getNestedClaim")
    class GetNestedClaim {

        @Test
        @DisplayName("should get top-level claim")
        void shouldGetTopLevelClaim() {
            var claims = Map.<String, Object>of("sub", "user123");

            var result = ClaimTranslator.getNestedClaim(claims, "sub");

            assertEquals("user123", result);
        }

        @Test
        @DisplayName("should get nested claim with dot notation")
        void shouldGetNestedClaim() {
            var claims = Map.<String, Object>of("realm_access", Map.of("roles", List.of("admin")));

            var result = ClaimTranslator.getNestedClaim(claims, "realm_access.roles");

            assertEquals(List.of("admin"), result);
        }

        @Test
        @DisplayName("should return null for missing claim")
        void shouldReturnNullForMissingClaim() {
            var claims = Map.<String, Object>of("sub", "user123");

            var result = ClaimTranslator.getNestedClaim(claims, "missing");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for missing nested path")
        void shouldReturnNullForMissingNestedPath() {
            var claims = Map.<String, Object>of("realm_access", Map.of("roles", List.of("admin")));

            var result = ClaimTranslator.getNestedClaim(claims, "realm_access.missing");

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("parseClaimValue")
    class ParseClaimValue {

        @Test
        @DisplayName("should parse array claim")
        void shouldParseArrayClaim() {
            var value = List.of("admin", "user");

            var result = ClaimTranslator.parseClaimValue(value, ClaimType.ARRAY);

            assertEquals(Set.of("admin", "user"), result);
        }

        @Test
        @DisplayName("should return empty set for non-list array type")
        void shouldReturnEmptyForNonListArray() {
            var result = ClaimTranslator.parseClaimValue("not-a-list", ClaimType.ARRAY);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should parse space-delimited claim")
        void shouldParseSpaceDelimitedClaim() {
            var result = ClaimTranslator.parseClaimValue("read write delete", ClaimType.SPACE_DELIMITED);

            assertEquals(Set.of("read", "write", "delete"), result);
        }

        @Test
        @DisplayName("should parse comma-delimited claim")
        void shouldParseCommaDelimitedClaim() {
            var result = ClaimTranslator.parseClaimValue("admin, user, guest", ClaimType.COMMA_DELIMITED);

            assertEquals(Set.of("admin", "user", "guest"), result);
        }

        @Test
        @DisplayName("should parse single value claim")
        void shouldParseSingleValueClaim() {
            var result = ClaimTranslator.parseClaimValue("admin", ClaimType.SINGLE);

            assertEquals(Set.of("admin"), result);
        }
    }

    @Nested
    @DisplayName("applyOperationToValue")
    class ApplyOperationToValue {

        @Test
        @DisplayName("should strip prefix")
        void shouldStripPrefix() {
            var op = new Operation.StripPrefix("ROLE_");

            var result = ClaimTranslator.applyOperationToValue("ROLE_admin", op);

            assertEquals("admin", result);
        }

        @Test
        @DisplayName("should not strip if prefix not present")
        void shouldNotStripIfPrefixNotPresent() {
            var op = new Operation.StripPrefix("ROLE_");

            var result = ClaimTranslator.applyOperationToValue("admin", op);

            assertEquals("admin", result);
        }

        @Test
        @DisplayName("should replace substring")
        void shouldReplaceSubstring() {
            var op = new Operation.Replace("_", "-");

            var result = ClaimTranslator.applyOperationToValue("some_role_name", op);

            assertEquals("some-role-name", result);
        }

        @Test
        @DisplayName("should convert to lowercase")
        void shouldConvertToLowercase() {
            var op = new Operation.Lowercase();

            var result = ClaimTranslator.applyOperationToValue("ADMIN", op);

            assertEquals("admin", result);
        }

        @Test
        @DisplayName("should convert to uppercase")
        void shouldConvertToUppercase() {
            var op = new Operation.Uppercase();

            var result = ClaimTranslator.applyOperationToValue("admin", op);

            assertEquals("ADMIN", result);
        }

        @Test
        @DisplayName("should apply regex replacement")
        void shouldApplyRegex() {
            var op = new Operation.Regex("([a-z]+)_([a-z]+)", "$2-$1");

            var result = ClaimTranslator.applyOperationToValue("user_admin", op);

            assertEquals("admin-user", result);
        }
    }

    @Nested
    @DisplayName("translate")
    class Translate {

        @Test
        @DisplayName("should translate claims using full schema")
        void shouldTranslateClaimsWithFullSchema() {
            var schema = new TranslationConfigSchema(
                    1,
                    List.of(new ClaimSource("roles", "realm_access.roles", ClaimType.ARRAY)),
                    List.of(),
                    new Mappings(Map.of("admin", List.of("admin.read", "admin.write")), Map.of()),
                    new Defaults(true, false));

            var claims = Map.<String, Object>of("realm_access", Map.of("roles", List.of("admin", "user")));

            var result = ClaimTranslator.translate(schema, claims);

            assertEquals(Set.of("admin"), result.roles());
            assertTrue(result.permissions().containsAll(Set.of("admin.read", "admin.write")));
        }

        @Test
        @DisplayName("should include unmapped roles when includeUnmapped is true")
        void shouldIncludeUnmappedRoles() {
            var schema = new TranslationConfigSchema(
                    1,
                    List.of(new ClaimSource("roles", "roles", ClaimType.ARRAY)),
                    List.of(),
                    new Mappings(Map.of("admin", List.of("admin.read")), Map.of()),
                    new Defaults(true, true));

            var claims = Map.<String, Object>of("roles", List.of("admin", "custom-role"));

            var result = ClaimTranslator.translate(schema, claims);

            assertTrue(result.roles().contains("admin"));
            assertTrue(result.roles().contains("custom-role"));
        }

        @Test
        @DisplayName("should apply transforms before mapping")
        void shouldApplyTransformsBeforeMapping() {
            var schema = new TranslationConfigSchema(
                    1,
                    List.of(new ClaimSource("roles", "roles", ClaimType.ARRAY)),
                    List.of(new Transform("roles", List.of(new Operation.StripPrefix("ROLE_")))),
                    new Mappings(Map.of("admin", List.of("admin.access")), Map.of()),
                    new Defaults(true, false));

            var claims = Map.<String, Object>of("roles", List.of("ROLE_admin"));

            var result = ClaimTranslator.translate(schema, claims);

            assertTrue(result.roles().contains("admin"));
            assertTrue(result.permissions().contains("admin.access"));
        }

        @Test
        @DisplayName("should handle direct permissions mapping")
        void shouldHandleDirectPermissions() {
            var schema = new TranslationConfigSchema(
                    1,
                    List.of(new ClaimSource("scopes", "scope", ClaimType.SPACE_DELIMITED)),
                    List.of(),
                    new Mappings(Map.of(), Map.of("read:users", "users.read", "write:users", "users.write")),
                    new Defaults(true, false));

            var claims = Map.<String, Object>of("scope", "read:users write:users");

            var result = ClaimTranslator.translate(schema, claims);

            assertTrue(result.permissions().contains("users.read"));
            assertTrue(result.permissions().contains("users.write"));
        }
    }
}
