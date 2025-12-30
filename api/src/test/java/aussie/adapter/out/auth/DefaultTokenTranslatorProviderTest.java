package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DefaultTokenTranslatorProvider.
 */
@DisplayName("DefaultTokenTranslatorProvider")
class DefaultTokenTranslatorProviderTest {

    private DefaultTokenTranslatorProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultTokenTranslatorProvider();
    }

    @Nested
    @DisplayName("Provider metadata")
    class ProviderMetadata {

        @Test
        @DisplayName("should have name 'default'")
        void shouldHaveDefaultName() {
            assertEquals("default", provider.name());
        }

        @Test
        @DisplayName("should have priority 100")
        void shouldHavePriority100() {
            assertEquals(100, provider.priority());
        }

        @Test
        @DisplayName("should always be available")
        void shouldAlwaysBeAvailable() {
            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("should provide health check response")
        void shouldProvideHealthCheck() {
            var response = provider.healthCheck();

            assertTrue(response.isPresent());
            assertEquals("token-translator-default", response.get().getName());
        }
    }

    @Nested
    @DisplayName("Role extraction")
    class RoleExtraction {

        @Test
        @DisplayName("should extract roles from roles claim")
        void shouldExtractRolesFromClaim() {
            var claims = Map.<String, Object>of("roles", List.of("admin", "user"));

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertEquals(Set.of("admin", "user"), result.roles());
        }

        @Test
        @DisplayName("should return empty roles when claim missing")
        void shouldReturnEmptyRolesWhenMissing() {
            var claims = Map.<String, Object>of();

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertTrue(result.roles().isEmpty());
        }

        @Test
        @DisplayName("should return empty roles when claim is not a list")
        void shouldReturnEmptyRolesWhenNotList() {
            var claims = Map.<String, Object>of("roles", "admin");

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertTrue(result.roles().isEmpty());
        }

        @Test
        @DisplayName("should convert non-string list elements to strings")
        void shouldConvertNonStringElements() {
            var claims = Map.<String, Object>of("roles", List.of("admin", 123, true));

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertEquals(Set.of("admin", "123", "true"), result.roles());
        }
    }

    @Nested
    @DisplayName("Permission extraction")
    class PermissionExtraction {

        @Test
        @DisplayName("should extract permissions from permissions claim")
        void shouldExtractPermissionsFromClaim() {
            var claims = Map.<String, Object>of("permissions", List.of("read", "write"));

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertEquals(Set.of("read", "write"), result.permissions());
        }

        @Test
        @DisplayName("should return empty permissions when claim missing")
        void shouldReturnEmptyPermissionsWhenMissing() {
            var claims = Map.<String, Object>of();

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertTrue(result.permissions().isEmpty());
        }

        @Test
        @DisplayName("should return empty permissions when claim is not a list")
        void shouldReturnEmptyPermissionsWhenNotList() {
            var claims = Map.<String, Object>of("permissions", "read");

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertTrue(result.permissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Combined extraction")
    class CombinedExtraction {

        @Test
        @DisplayName("should extract both roles and permissions")
        void shouldExtractBothRolesAndPermissions() {
            var claims = Map.<String, Object>of(
                    "roles", List.of("admin"),
                    "permissions", List.of("read", "write"));

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
            assertEquals(Set.of("read", "write"), result.permissions());
        }

        @Test
        @DisplayName("should return empty attributes")
        void shouldReturnEmptyAttributes() {
            var claims = Map.<String, Object>of("roles", List.of("admin"), "customClaim", "value");

            var result = provider.translate("issuer", "subject", claims).await().indefinitely();

            assertTrue(result.attributes().isEmpty());
        }
    }
}
