package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.core.model.auth.ApiKey;
import aussie.core.port.in.ApiKeyManagement;

@DisplayName("ApiKeyIdentityProvider")
class ApiKeyIdentityProviderTest {

    private ApiKeyManagement apiKeyManagement;
    private GatewayMetrics metrics;
    private SecurityMonitor securityMonitor;
    private AuthenticationRequestContext context;
    private ApiKeyIdentityProvider provider;

    @BeforeEach
    void setUp() {
        apiKeyManagement = mock(ApiKeyManagement.class);
        metrics = mock(GatewayMetrics.class);
        securityMonitor = mock(SecurityMonitor.class);
        context = mock(AuthenticationRequestContext.class);

        provider = new ApiKeyIdentityProvider(apiKeyManagement, metrics, securityMonitor);
    }

    private ApiKey testApiKey(Set<String> permissions) {
        return ApiKey.builder("key-1", "hash123")
                .name("test-key")
                .permissions(permissions)
                .teamId("team-1")
                .expiresAt(Instant.now().plusSeconds(86400))
                .build();
    }

    @Nested
    @DisplayName("getRequestType()")
    class GetRequestTypeTests {

        @Test
        @DisplayName("should return ApiKeyAuthenticationRequest class")
        void shouldReturnRequestType() {
            assertEquals(ApiKeyAuthenticationRequest.class, provider.getRequestType());
        }
    }

    @Nested
    @DisplayName("authenticate()")
    class AuthenticateTests {

        @Test
        @DisplayName("should build identity for valid API key")
        void shouldBuildIdentityForValidKey() {
            var apiKey = testApiKey(Set.of("service.config.read", "service.config.update"));
            when(apiKeyManagement.validate("sk_live_abc123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_abc123");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertEquals("test-key", identity.getPrincipal().getName());
            assertTrue(identity.getRoles().contains("service.config.read"));
            assertTrue(identity.getRoles().contains("service.config.update"));
            assertEquals("key-1", identity.getAttribute("keyId"));
            assertEquals("key-1", identity.getAttribute("principalId"));
            assertEquals("key-1", identity.getAttribute("credentialId"));
            assertEquals("team-1", identity.getAttribute("teamId"));
        }

        @Test
        @DisplayName("should throw AuthenticationFailedException for invalid key")
        void shouldThrowForInvalidKey() {
            when(apiKeyManagement.validate("invalid-key"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var request = new ApiKeyAuthenticationRequest("invalid-key");
            assertThrows(
                    AuthenticationFailedException.class,
                    () -> provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1)));

            verify(metrics).recordAuthFailure("invalid_key", null);
            verify(securityMonitor).recordAuthFailure(null, "invalid_key", "api_key");
        }

        @Test
        @DisplayName("should grant admin role for wildcard permission")
        void shouldGrantAdminForWildcard() {
            var apiKey = testApiKey(Set.of("*"));
            when(apiKeyManagement.validate("sk_live_admin"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_admin");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertTrue(identity.getRoles().contains("admin"));
        }

        @Test
        @DisplayName("should include effective permissions with wildcard expansion")
        void shouldIncludeEffectivePermissions() {
            var apiKey = testApiKey(Set.of("*"));
            when(apiKeyManagement.validate("sk_live_admin"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_admin");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            Set<String> permissions = identity.getAttribute("permissions");
            assertNotNull(permissions);
            assertTrue(permissions.contains("aussie:admin"));
            assertTrue(permissions.contains("*"));
        }

        @Test
        @DisplayName("should handle API key without team ID")
        void shouldHandleKeyWithoutTeamId() {
            var apiKey = ApiKey.builder("key-2", "hash456")
                    .name("no-team-key")
                    .permissions(Set.of("service.config.read"))
                    .build();
            when(apiKeyManagement.validate("sk_live_noteam"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_noteam");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertNull(identity.getAttribute("teamId"));
        }

        @Test
        @DisplayName("should handle API key without expiry")
        void shouldHandleKeyWithoutExpiry() {
            var apiKey = ApiKey.builder("key-3", "hash789")
                    .name("no-expiry-key")
                    .permissions(Set.of("service.config.read"))
                    .build();
            when(apiKeyManagement.validate("sk_live_noexpiry"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_noexpiry");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertNull(identity.getAttribute("expiresAt"));
        }

        @Test
        @DisplayName("should include expiresAt attribute when key has expiry")
        void shouldIncludeExpiresAtAttribute() {
            var expiresAt = Instant.now().plusSeconds(86400);
            var apiKey = ApiKey.builder("key-4", "hashexp")
                    .name("expiring-key")
                    .permissions(Set.of("service.config.read"))
                    .expiresAt(expiresAt)
                    .build();
            when(apiKeyManagement.validate("sk_live_expiring"))
                    .thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var request = new ApiKeyAuthenticationRequest("sk_live_expiring");
            var identity = provider.authenticate(request, context).await().atMost(Duration.ofSeconds(1));

            assertEquals(expiresAt, identity.getAttribute("expiresAt"));
        }
    }

    @Nested
    @DisplayName("ApiKeyPrincipal")
    class ApiKeyPrincipalTests {

        @Test
        @DisplayName("should expose key ID and name")
        void shouldExposeKeyIdAndName() {
            var principal = new ApiKeyIdentityProvider.ApiKeyPrincipal("key-1", "test-key");

            assertEquals("test-key", principal.getName());
            assertEquals("key-1", principal.getKeyId());
        }
    }
}
