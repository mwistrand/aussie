package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.ApiKeyIdentityProvider.ApiKeyPrincipal;
import aussie.adapter.in.dto.CreateApiKeyRequest;
import aussie.core.model.auth.ApiKey;
import aussie.core.model.auth.ApiKeyCreateResult;
import aussie.core.port.in.ApiKeyManagement;

@DisplayName("ApiKeyResource")
@ExtendWith(MockitoExtension.class)
class ApiKeyResourceUnitTest {

    @Mock
    private ApiKeyManagement apiKeyService;

    @Mock
    private SecurityIdentity identity;

    private ApiKeyResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(identity.hasRole("admin")).thenReturn(true);
        resource = new ApiKeyResource(apiKeyService, identity);
    }

    private ApiKey createApiKey(String id, String name, String teamId, Instant expiresAt) {
        return ApiKey.builder(id, "hash-" + id)
                .name(name)
                .description("test key")
                .teamId(teamId)
                .permissions(Set.of("admin"))
                .createdBy("creator")
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("listKeys delegates pagination")
    void listKeysDelegatesPagination() {
        final var keys = List.of(createApiKey("key-1", "Key 1", null, null));
        when(apiKeyService.list(25, 10)).thenReturn(Uni.createFrom().item(keys));

        final var result = resource.listKeys(25, 10).await().atMost(Duration.ofSeconds(5));

        assertEquals(keys, result);
    }

    @Nested
    @DisplayName("createKey")
    class CreateKey {

        @Test
        @DisplayName("should pass null ttl when ttlDays is null")
        @SuppressWarnings("unchecked")
        void shouldPassNullTtlWhenTtlDaysNull() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k1", "my-key", null, null);
            var createResult = new ApiKeyCreateResult("k1", "plaintext-key", apiKey);
            when(apiKeyService.create(eq("my-key"), eq("desc"), isNull(), any(), isNull(), eq("key-1")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key", "desc", null, Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            assertEquals("\"1\"", response.getHeaderString("ETag"));
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("k1", body.get("keyId"));
            assertEquals("plaintext-key", body.get("key"));
            assertEquals("my-key", body.get("name"));
        }

        @Test
        @DisplayName("should convert ttlDays to Duration when non-null")
        @SuppressWarnings("unchecked")
        void shouldConvertTtlDaysWhenNonNull() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k2", "my-key-2", null, null);
            var createResult = new ApiKeyCreateResult("k2", "plaintext-key-2", apiKey);
            when(apiKeyService.create(
                            eq("my-key-2"), eq("desc"), isNull(), any(), eq(Duration.ofDays(30)), eq("key-1")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-2", "desc", null, Set.of("admin"), 30);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("should include teamId in response when present")
        @SuppressWarnings("unchecked")
        void shouldIncludeTeamIdWhenPresent() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k3", "my-key-3", "team-alpha", null);
            var createResult = new ApiKeyCreateResult("k3", "plaintext-key-3", apiKey);
            when(apiKeyService.create(eq("my-key-3"), isNull(), eq("team-alpha"), any(), isNull(), eq("key-1")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-3", null, "team-alpha", Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            var body = (Map<String, Object>) response.getEntity();
            assertEquals("team-alpha", body.get("teamId"));
        }

        @Test
        @DisplayName("should not include teamId in response when null")
        @SuppressWarnings("unchecked")
        void shouldNotIncludeTeamIdWhenNull() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k4", "my-key-4", null, null);
            var createResult = new ApiKeyCreateResult("k4", "plaintext-key-4", apiKey);
            when(apiKeyService.create(eq("my-key-4"), isNull(), isNull(), any(), isNull(), eq("key-1")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-4", null, null, Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            var body = (Map<String, Object>) response.getEntity();
            assertFalse(body.containsKey("teamId"));
        }

        @Test
        @DisplayName("should include expiresAt in response when present on metadata")
        @SuppressWarnings("unchecked")
        void shouldIncludeExpiresAtWhenPresent() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var expiresAt = Instant.parse("2026-06-01T00:00:00Z");
            var apiKey = createApiKey("k5", "my-key-5", null, expiresAt);
            var createResult = new ApiKeyCreateResult("k5", "plaintext-key-5", apiKey);
            when(apiKeyService.create(anyString(), any(), any(), any(), any(), anyString()))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-5", null, null, Set.of("admin"), 30);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            var body = (Map<String, Object>) response.getEntity();
            assertEquals("2026-06-01T00:00:00Z", body.get("expiresAt"));
        }

        @Test
        @DisplayName("should not include expiresAt in response when null on metadata")
        @SuppressWarnings("unchecked")
        void shouldNotIncludeExpiresAtWhenNull() {
            var principal = new ApiKeyPrincipal("key-1", "test-key");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k6", "my-key-6", null, null);
            var createResult = new ApiKeyCreateResult("k6", "plaintext-key-6", apiKey);
            when(apiKeyService.create(anyString(), any(), any(), any(), any(), anyString()))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-6", null, null, Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            var body = (Map<String, Object>) response.getEntity();
            assertFalse(body.containsKey("expiresAt"));
        }
    }

    @Nested
    @DisplayName("getKey")
    class GetKey {

        @Test
        @DisplayName("should return key when found")
        void shouldReturnKeyWhenFound() {
            var apiKey = createApiKey("k1", "my-key", null, null);
            when(apiKeyService.get("k1")).thenReturn(Uni.createFrom().item(Optional.of(apiKey)));

            var response = resource.getKey("k1").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertEquals(apiKey, response.getEntity());
            assertEquals("\"1\"", response.getHeaderString("ETag"));
        }

        @Test
        @DisplayName("should throw HttpProblem when key not found")
        void shouldThrowWhenKeyNotFound() {
            when(apiKeyService.get("unknown")).thenReturn(Uni.createFrom().item(Optional.empty()));

            var ex = assertThrows(
                    HttpProblem.class, () -> resource.getKey("unknown").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("revokeKey")
    class RevokeKey {

        @Test
        @DisplayName("should return 204 when revocation succeeds")
        void shouldReturn204WhenRevocationSucceeds() {
            when(apiKeyService.revoke("k1")).thenReturn(Uni.createFrom().item(true));

            var response = resource.revokeKey("k1").await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("should throw HttpProblem when key not found")
        void shouldThrowWhenKeyNotFound() {
            when(apiKeyService.revoke("unknown")).thenReturn(Uni.createFrom().item(false));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.revokeKey("unknown").await().atMost(Duration.ofSeconds(5)));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should not let an unscoped caller bypass ownership checks")
        void shouldNotLetUnscopedCallerBypassOwnershipChecks() {
            var scopedIdentity = mock(SecurityIdentity.class);
            when(scopedIdentity.getPrincipal()).thenReturn(() -> "user-1");
            when(apiKeyService.get("k1"))
                    .thenReturn(Uni.createFrom().item(Optional.of(createApiKey("k1", "key", "team-a", null))));
            var scopedResource = new ApiKeyResource(apiKeyService, scopedIdentity);

            assertThrows(
                    HttpProblem.class,
                    () -> scopedResource.revokeKey("k1").await().atMost(Duration.ofSeconds(5)));
            verify(apiKeyService, never()).revoke("k1");
        }
    }

    @Nested
    @DisplayName("getCreatorId")
    class GetCreatorId {

        @Test
        @DisplayName("should return keyId when principal is ApiKeyPrincipal")
        @SuppressWarnings("unchecked")
        void shouldReturnKeyIdWhenApiKeyPrincipal() {
            var principal = new ApiKeyPrincipal("key-abc", "test-key-name");
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = ApiKey.builder("k1", "hash-k1")
                    .name("my-key")
                    .permissions(Set.of("admin"))
                    .createdBy("key-abc")
                    .build();
            var createResult = new ApiKeyCreateResult("k1", "plaintext", apiKey);
            when(apiKeyService.create(anyString(), any(), any(), any(), any(), eq("key-abc")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key", null, null, Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("key-abc", body.get("createdBy"));
        }

        @Test
        @DisplayName("should return principal name when not ApiKeyPrincipal")
        @SuppressWarnings("unchecked")
        void shouldReturnPrincipalNameWhenNotApiKeyPrincipal() {
            var principal = new Principal() {
                @Override
                public String getName() {
                    return "jwt-user@example.com";
                }
            };
            when(identity.getPrincipal()).thenReturn(principal);

            var apiKey = createApiKey("k2", "my-key-2", null, null);
            var createResult = new ApiKeyCreateResult("k2", "plaintext-2", apiKey);
            when(apiKeyService.create(anyString(), any(), any(), any(), any(), eq("jwt-user@example.com")))
                    .thenReturn(Uni.createFrom().item(createResult));

            var request = new CreateApiKeyRequest("my-key-2", null, null, Set.of("admin"), null);
            var response = resource.createKey(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        }
    }
}
