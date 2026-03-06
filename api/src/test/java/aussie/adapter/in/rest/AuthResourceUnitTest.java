package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Instant;
import java.util.Set;

import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.ApiKeyIdentityProvider.ApiKeyPrincipal;

@DisplayName("AuthResource")
@ExtendWith(MockitoExtension.class)
class AuthResourceUnitTest {

    @Mock
    private SecurityIdentity identity;

    private AuthResource resource;

    @BeforeEach
    void setUp() {
        resource = new AuthResource(identity);
    }

    @Nested
    @DisplayName("whoami")
    class Whoami {

        @Test
        @DisplayName("should include keyId and name when principal is ApiKeyPrincipal")
        void shouldIncludeKeyIdWhenApiKeyPrincipal() {
            var principal = new ApiKeyPrincipal("key-abc", "my-api-key");
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getRoles()).thenReturn(Set.of("admin"));
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertEquals("key-abc", result.get("keyId"));
            assertEquals("my-api-key", result.get("name"));
        }

        @Test
        @DisplayName("should include only name when principal is not ApiKeyPrincipal")
        void shouldIncludeOnlyNameWhenNotApiKeyPrincipal() {
            var principal = new Principal() {
                @Override
                public String getName() {
                    return "jwt-user@example.com";
                }
            };
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getRoles()).thenReturn(Set.of());
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertEquals("jwt-user@example.com", result.get("name"));
            assertFalse(result.containsKey("keyId"));
        }

        @Test
        @DisplayName("should include permissions when non-null")
        @SuppressWarnings("unchecked")
        void shouldIncludePermissionsWhenNonNull() {
            var principal = new ApiKeyPrincipal("key-1", "test");
            when(identity.getPrincipal()).thenReturn(principal);
            var perms = Set.of("read", "write");
            when(identity.getAttribute("permissions")).thenReturn(perms);
            when(identity.getRoles()).thenReturn(Set.of());
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertEquals(perms, result.get("permissions"));
        }

        @Test
        @DisplayName("should not include permissions when null")
        void shouldNotIncludePermissionsWhenNull() {
            var principal = new ApiKeyPrincipal("key-1", "test");
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getRoles()).thenReturn(Set.of());
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertFalse(result.containsKey("permissions"));
        }

        @Test
        @DisplayName("should include expiresAt when non-null")
        void shouldIncludeExpiresAtWhenNonNull() {
            var principal = new ApiKeyPrincipal("key-1", "test");
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getRoles()).thenReturn(Set.of());
            var expiresAt = Instant.parse("2026-12-01T00:00:00Z");
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(expiresAt);

            var result = resource.whoami();

            assertEquals("2026-12-01T00:00:00Z", result.get("expiresAt"));
        }

        @Test
        @DisplayName("should not include expiresAt when null")
        void shouldNotIncludeExpiresAtWhenNull() {
            var principal = new ApiKeyPrincipal("key-1", "test");
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            when(identity.getRoles()).thenReturn(Set.of());
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertFalse(result.containsKey("expiresAt"));
        }

        @Test
        @DisplayName("should always include roles")
        void shouldAlwaysIncludeRoles() {
            var principal = new ApiKeyPrincipal("key-1", "test");
            when(identity.getPrincipal()).thenReturn(principal);
            when(identity.getAttribute("permissions")).thenReturn(null);
            var roles = Set.of("admin", "viewer");
            when(identity.getRoles()).thenReturn(roles);
            when(identity.<Instant>getAttribute("expiresAt")).thenReturn(null);

            var result = resource.whoami();

            assertEquals(roles, result.get("roles"));
        }
    }
}
