package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenTranslationConfig;

/**
 * Unit tests for ConfigTokenTranslatorProvider.
 */
@DisplayName("ConfigTokenTranslatorProvider")
@ExtendWith(MockitoExtension.class)
class ConfigTokenTranslatorProviderTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String SUBJECT = "user-123";

    @TempDir
    Path tempDir;

    @Mock
    private TokenTranslationConfig config;

    @Mock
    private TokenTranslationConfig.Config configConfig;

    private ObjectMapper objectMapper;
    private ConfigTokenTranslatorProvider provider;
    private Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        configFile = tempDir.resolve("translation-config.json");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(configFile)) {
            Files.delete(configFile);
        }
    }

    private void initProviderWithConfig(String configContent) throws IOException {
        Files.writeString(configFile, configContent);
        when(config.config()).thenReturn(configConfig);
        when(configConfig.path()).thenReturn(Optional.of(configFile.toString()));

        provider = new ConfigTokenTranslatorProvider(config, objectMapper);
        provider.init();
    }

    private void initProviderWithoutConfig() {
        when(config.config()).thenReturn(configConfig);
        when(configConfig.path()).thenReturn(Optional.empty());

        provider = new ConfigTokenTranslatorProvider(config, objectMapper);
        provider.init();
    }

    @Nested
    @DisplayName("Provider metadata")
    class ProviderMetadata {

        @Test
        @DisplayName("should have name 'config'")
        void shouldHaveConfigName() throws IOException {
            initProviderWithoutConfig();
            assertEquals("config", provider.name());
        }

        @Test
        @DisplayName("should have priority 50")
        void shouldHavePriority50() throws IOException {
            initProviderWithoutConfig();
            assertEquals(50, provider.priority());
        }

        @Test
        @DisplayName("should be unavailable when no config path set")
        void shouldBeUnavailableWhenNoConfig() throws IOException {
            initProviderWithoutConfig();
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("should be available when valid config loaded")
        void shouldBeAvailableWithValidConfig() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [],
                      "transforms": [],
                      "mappings": { "roleToPermissions": {}, "directPermissions": {} },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("should provide health check response when available")
        void shouldProvideHealthCheckWhenAvailable() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": { "roleToPermissions": {}, "directPermissions": {} },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var response = provider.healthCheck();
            assertTrue(response.isPresent());
            assertEquals("token-translator-config", response.get().getName());
        }
    }

    @Nested
    @DisplayName("Claim extraction")
    class ClaimExtraction {

        @Test
        @DisplayName("should extract roles from array claim")
        void shouldExtractFromArrayClaim() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admin": ["perm1"], "user": ["perm2"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("roles", List.of("admin", "user"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin", "user"), result.roles());
        }

        @Test
        @DisplayName("should extract from space-delimited scope claim")
        void shouldExtractFromSpaceDelimitedClaim() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "scopes", "claim": "scope", "type": "space-delimited" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {},
                        "directPermissions": { "read": "service.read", "write": "service.write" }
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("scope", "read write admin");
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("service.read", "service.write"), result.permissions());
        }

        @Test
        @DisplayName("should extract from comma-delimited claim")
        void shouldExtractFromCommaDelimitedClaim() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "comma-delimited" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admins": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", "admins, users, guests");
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admins"), result.roles());
            assertEquals(Set.of("admin.all"), result.permissions());
        }

        @Test
        @DisplayName("should extract from nested claim using dot notation")
        void shouldExtractFromNestedClaim() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "realmRoles", "claim": "realm_access.roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admin": ["full.access"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("realm_access", Map.of("roles", List.of("admin", "viewer")));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
        }

        @Test
        @DisplayName("should return empty when claim not present")
        void shouldReturnEmptyWhenClaimMissing() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": { "roleToPermissions": {}, "directPermissions": {} },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("other", "value");
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertTrue(result.roles().isEmpty());
            assertTrue(result.permissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Transform operations")
    class TransformOperations {

        @Test
        @DisplayName("should strip prefix from values")
        void shouldStripPrefix() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [{ "type": "strip-prefix", "value": "APP_" }] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "admin": ["admin.all"], "user": ["user.basic"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("APP_admin", "APP_user", "other"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin", "user"), result.roles());
        }

        @Test
        @DisplayName("should replace characters")
        void shouldReplace() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [{ "type": "replace", "from": ":", "to": "." }] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "service.admin": ["admin"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("service:admin"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("service.admin"), result.roles());
        }

        @Test
        @DisplayName("should convert to lowercase")
        void shouldLowercase() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [{ "type": "lowercase" }] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "admin": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("ADMIN", "User"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
        }

        @Test
        @DisplayName("should convert to uppercase")
        void shouldUppercase() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [{ "type": "uppercase" }] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "ADMIN": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("admin", "User"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("ADMIN"), result.roles());
        }

        @Test
        @DisplayName("should apply regex replacement")
        void shouldApplyRegex() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [{ "type": "regex", "pattern": "group-(\\\\w+)", "replacement": "$1" }] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "admin": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("group-admin", "group-user"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
        }

        @Test
        @DisplayName("should apply multiple transforms in order")
        void shouldApplyMultipleTransforms() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "groups", "claim": "groups", "type": "array" }],
                      "transforms": [
                        { "source": "groups", "operations": [
                          { "type": "strip-prefix", "value": "APP_" },
                          { "type": "lowercase" }
                        ] }
                      ],
                      "mappings": {
                        "roleToPermissions": { "admin": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("groups", List.of("APP_ADMIN"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
        }
    }

    @Nested
    @DisplayName("Mappings")
    class MappingTests {

        @Test
        @DisplayName("should map roles to permissions")
        void shouldMapRolesToPermissions() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {
                          "admin": ["service.create", "service.delete", "service.update"],
                          "viewer": ["service.read"]
                        },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("roles", List.of("admin", "viewer"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin", "viewer"), result.roles());
            assertEquals(
                    Set.of("service.create", "service.delete", "service.update", "service.read"), result.permissions());
        }

        @Test
        @DisplayName("should map direct permissions")
        void shouldMapDirectPermissions() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "scopes", "claim": "scope", "type": "space-delimited" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {},
                        "directPermissions": {
                          "read:users": "users.read",
                          "write:users": "users.write"
                        }
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("scope", "read:users write:users openid");
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertTrue(result.roles().isEmpty());
            assertEquals(Set.of("users.read", "users.write"), result.permissions());
        }

        @Test
        @DisplayName("should include unmapped values when configured")
        void shouldIncludeUnmappedValues() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admin": ["admin.all"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": false, "includeUnmapped": true }
                    }
                    """);

            var claims = Map.<String, Object>of("roles", List.of("admin", "custom-role", "other"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin", "custom-role", "other"), result.roles());
        }
    }

    @Nested
    @DisplayName("IdP-specific configurations")
    class IdPConfigurations {

        @Test
        @DisplayName("should handle Auth0 token structure")
        void shouldHandleAuth0Token() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [
                        { "name": "permissions", "claim": "permissions", "type": "array" }
                      ],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {},
                        "directPermissions": {
                          "read:services": "service.config.read",
                          "write:services": "service.config.update"
                        }
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("permissions", List.of("read:services", "write:services"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("service.config.read", "service.config.update"), result.permissions());
        }

        @Test
        @DisplayName("should handle Keycloak token structure")
        void shouldHandleKeycloakToken() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [
                        { "name": "realmRoles", "claim": "realm_access.roles", "type": "array" }
                      ],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {
                          "aussie-admin": ["admin"],
                          "aussie-viewer": ["service.config.read", "apikeys.read"]
                        },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims =
                    Map.<String, Object>of("realm_access", Map.of("roles", List.of("aussie-admin", "offline_access")));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("aussie-admin"), result.roles());
            assertEquals(Set.of("admin"), result.permissions());
        }

        @Test
        @DisplayName("should handle Azure AD token structure with GUID groups")
        void shouldHandleAzureAdToken() throws IOException {
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [
                        { "name": "groups", "claim": "groups", "type": "array" }
                      ],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": {
                          "12345678-1234-1234-1234-123456789abc": ["admin"],
                          "87654321-4321-4321-4321-cba987654321": ["service.config.read"]
                        },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of(
                    "groups", List.of("12345678-1234-1234-1234-123456789abc", "some-other-group"));
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("12345678-1234-1234-1234-123456789abc"), result.roles());
            assertEquals(Set.of("admin"), result.permissions());
        }
    }

    @Nested
    @DisplayName("Config reload")
    class ConfigReload {

        @Test
        @DisplayName("should reload configuration from file")
        void shouldReloadConfig() throws IOException {
            // Initial config
            initProviderWithConfig(
                    """
                    {
                      "version": 1,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admin": ["old.permission"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            var claims = Map.<String, Object>of("roles", List.of("admin"));
            var result1 = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();
            assertEquals(Set.of("old.permission"), result1.permissions());

            // Update config file
            Files.writeString(
                    configFile,
                    """
                    {
                      "version": 2,
                      "sources": [{ "name": "roles", "claim": "roles", "type": "array" }],
                      "transforms": [],
                      "mappings": {
                        "roleToPermissions": { "admin": ["new.permission"] },
                        "directPermissions": {}
                      },
                      "defaults": { "denyIfNoMatch": true, "includeUnmapped": false }
                    }
                    """);

            // Reload
            provider.reloadConfig();

            var result2 = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();
            assertEquals(Set.of("new.permission"), result2.permissions());
        }
    }
}
