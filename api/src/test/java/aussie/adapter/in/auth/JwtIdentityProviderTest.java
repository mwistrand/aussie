package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.port.in.RoleManagement;
import aussie.core.service.auth.TokenTranslationService;
import aussie.core.service.auth.TokenValidationService;

@DisplayName("JwtIdentityProvider")
class JwtIdentityProviderTest {

    private JwtIdentityProvider provider;
    private TokenValidationService tokenValidationService;
    private RoleManagement roleManagement;
    private TokenTranslationService tokenTranslationService;
    private AuthenticationRequestContext context;

    @BeforeEach
    void setUp() {
        tokenValidationService = mock(TokenValidationService.class);
        roleManagement = mock(RoleManagement.class);
        tokenTranslationService = mock(TokenTranslationService.class);
        context = mock(AuthenticationRequestContext.class);

        provider = new JwtIdentityProvider(tokenValidationService, roleManagement, tokenTranslationService);
    }

    @Nested
    @DisplayName("authenticate")
    class AuthenticateTests {

        @Test
        @DisplayName("should return null when token validation is not enabled")
        void shouldReturnNullWhenNotEnabled() {
            when(tokenValidationService.isEnabled()).thenReturn(false);

            var request = new JwtAuthenticationRequest("some-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNull(result);
        }

        @Test
        @DisplayName("should throw AuthenticationFailedException when token is invalid")
        void shouldThrowWhenTokenInvalid() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("Token expired")));

            var request = new JwtAuthenticationRequest("invalid-token");

            assertThrows(
                    AuthenticationFailedException.class,
                    () -> provider.authenticate(request, context).await().indefinitely());
        }

        @Test
        @DisplayName("should return null when no token provided")
        void shouldReturnNullWhenNoToken() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.NoToken()));

            var request = new JwtAuthenticationRequest("");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNull(result);
        }

        @Test
        @DisplayName("should return SecurityIdentity with correct subject when token is valid")
        void shouldReturnIdentityWhenTokenValid() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of(
                    "sub",
                    "test-user",
                    "name",
                    "Test User",
                    "roles",
                    List.of("demo-service.admin"),
                    "permissions",
                    List.of("admin:read"));
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom()
                            .item(new TranslatedClaims(Set.of("demo-service.admin"), Set.of("admin:read"), Map.of())));
            when(roleManagement.expandRoles(anySet()))
                    .thenReturn(Uni.createFrom().item(Set.of("service.config.read", "service.config.write")));

            var request = new JwtAuthenticationRequest("valid-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNotNull(result);
            assertEquals("Test User", result.getPrincipal().getName());
            assertTrue(result.getAttribute("roles") instanceof List);
            assertTrue(result.getAttribute("permissions") instanceof Set);
        }

        @Test
        @DisplayName("should expand roles to permissions")
        void shouldExpandRolesToPermissions() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of(
                    "sub", "test-user", "roles", List.of("demo-service.admin", "demo-service.dev"));
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom()
                            .item(new TranslatedClaims(
                                    Set.of("demo-service.admin", "demo-service.dev"), Set.of(), Map.of())));
            when(roleManagement.expandRoles(anySet()))
                    .thenReturn(Uni.createFrom().item(Set.of("admin:read", "admin:write", "service:read")));

            var request = new JwtAuthenticationRequest("valid-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            Set<String> permissions = (Set<String>) result.getAttribute("permissions");
            assertTrue(permissions.contains("admin:read"));
            assertTrue(permissions.contains("admin:write"));
            assertTrue(permissions.contains("service:read"));
        }

        @Test
        @DisplayName("should combine direct permissions with role-expanded permissions")
        void shouldCombineDirectAndRolePermissions() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of(
                    "sub", "test-user",
                    "roles", List.of("demo-service.dev"),
                    "permissions", List.of("custom:permission"));
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom()
                            .item(new TranslatedClaims(
                                    Set.of("demo-service.dev"), Set.of("custom:permission"), Map.of())));
            when(roleManagement.expandRoles(anySet()))
                    .thenReturn(Uni.createFrom().item(Set.of("dev:read")));

            var request = new JwtAuthenticationRequest("valid-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            Set<String> permissions = (Set<String>) result.getAttribute("permissions");
            assertTrue(permissions.contains("custom:permission")); // Direct permission
            assertTrue(permissions.contains("dev:read")); // Role-expanded permission
        }

        @Test
        @DisplayName("should propagate exception when role expansion fails")
        void shouldPropagateExceptionWhenRoleExpansionFails() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of("sub", "test-user", "roles", List.of("demo-service.dev"));
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom()
                            .item(new TranslatedClaims(Set.of("demo-service.dev"), Set.of(), Map.of())));
            when(roleManagement.expandRoles(anySet()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Database connection failed")));

            var request = new JwtAuthenticationRequest("valid-token");

            assertThrows(
                    RuntimeException.class,
                    () -> provider.authenticate(request, context).await().indefinitely());
        }

        @Test
        @DisplayName("should propagate exception when token translation fails")
        void shouldPropagateExceptionWhenTokenTranslationFails() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of("sub", "test-user");
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Translation provider unavailable")));

            var request = new JwtAuthenticationRequest("valid-token");

            assertThrows(
                    RuntimeException.class,
                    () -> provider.authenticate(request, context).await().indefinitely());
        }

        @Test
        @DisplayName("should handle empty roles list")
        void shouldHandleEmptyRolesList() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of("sub", "test-user", "roles", List.of());
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(TranslatedClaims.empty()));
            // Note: expandRoles should not be called when roles list is empty

            var request = new JwtAuthenticationRequest("valid-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            Set<String> permissions = (Set<String>) result.getAttribute("permissions");
            assertTrue(permissions.isEmpty());
        }

        @Test
        @DisplayName("should handle missing roles claim")
        void shouldHandleMissingRolesClaim() {
            when(tokenValidationService.isEnabled()).thenReturn(true);

            var claims = Map.<String, Object>of("sub", "test-user");
            var validResult = new TokenValidationResult.Valid(
                    "test-user", "demo-app", claims, Instant.now().plusSeconds(3600));

            when(tokenValidationService.validate(any()))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(tokenTranslationService.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(TranslatedClaims.empty()));

            var request = new JwtAuthenticationRequest("valid-token");
            SecurityIdentity result =
                    provider.authenticate(request, context).await().indefinitely();

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            Set<String> permissions = (Set<String>) result.getAttribute("permissions");
            assertTrue(permissions.isEmpty());
        }
    }

    @Nested
    @DisplayName("getRequestType")
    class GetRequestTypeTests {

        @Test
        @DisplayName("should return JwtAuthenticationRequest class")
        void shouldReturnCorrectRequestType() {
            assertEquals(JwtAuthenticationRequest.class, provider.getRequestType());
        }
    }

    @Nested
    @DisplayName("JwtPrincipal")
    class JwtPrincipalTests {

        @Test
        @DisplayName("should use name claim when available")
        void shouldUseNameClaim() {
            var claims = Map.<String, Object>of("name", "Display Name");
            var principal = new JwtIdentityProvider.JwtPrincipal("subject", claims);

            assertEquals("Display Name", principal.getName());
            assertEquals("subject", principal.getSubject());
        }

        @Test
        @DisplayName("should use subject when name claim not available")
        void shouldUseSubjectWhenNoName() {
            var claims = Map.<String, Object>of();
            var principal = new JwtIdentityProvider.JwtPrincipal("subject-id", claims);

            assertEquals("subject-id", principal.getName());
        }

        @Test
        @DisplayName("should provide access to claims")
        void shouldProvideAccessToClaims() {
            var claims = Map.<String, Object>of("custom", "value");
            var principal = new JwtIdentityProvider.JwtPrincipal("subject", claims);

            assertEquals("value", principal.getClaim("custom"));
            assertEquals(claims, principal.getClaims());
        }

        @Test
        @DisplayName("should return immutable claims")
        void shouldReturnImmutableClaims() {
            var mutableClaims = new java.util.HashMap<String, Object>();
            mutableClaims.put("key", "value");
            var principal = new JwtIdentityProvider.JwtPrincipal("subject", mutableClaims);

            // Verify that modifying the original map doesn't affect the principal
            mutableClaims.put("key", "modified");
            assertEquals("value", principal.getClaim("key"));

            // Verify that the returned claims map is unmodifiable
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> principal.getClaims().put("new", "value"));
        }

        @Test
        @DisplayName("should handle null claims")
        void shouldHandleNullClaims() {
            var principal = new JwtIdentityProvider.JwtPrincipal("subject", null);

            assertEquals("subject", principal.getName());
            assertNull(principal.getClaim("anything"));
            assertNotNull(principal.getClaims());
            assertTrue(principal.getClaims().isEmpty());
        }
    }
}
