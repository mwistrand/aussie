package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.MultivaluedHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.memory.InMemoryApiKeyRepository;
import aussie.core.config.ApiKeyConfig;
import aussie.core.model.auth.AuthenticationResult;
import aussie.core.model.auth.Permission;
import aussie.core.service.auth.ApiKeyService;

@DisplayName("ApiKeyAuthProvider")
class ApiKeyAuthProviderTest {

    private ApiKeyAuthProvider provider;
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        var repository = new InMemoryApiKeyRepository();
        ApiKeyConfig config = () -> Optional.empty();
        apiKeyService = new ApiKeyService(repository, config);
        provider = new ApiKeyAuthProvider(apiKeyService);
    }

    @Nested
    @DisplayName("authenticate")
    class AuthenticateTests {

        @Test
        @DisplayName("should skip when no Authorization header present")
        void shouldSkipWhenNoAuthorizationHeader() {
            var headers = new MultivaluedHashMap<String, String>();

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Skip.class, result);
        }

        @Test
        @DisplayName("should skip when Authorization header is empty")
        void shouldSkipWhenAuthorizationHeaderIsEmpty() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "");

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Skip.class, result);
        }

        @Test
        @DisplayName("should skip when Authorization header is not Bearer token")
        void shouldSkipWhenNotBearerToken() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Basic dXNlcjpwYXNz");

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Skip.class, result);
        }

        @Test
        @DisplayName("should fail when Bearer token is empty")
        void shouldFailWhenBearerTokenIsEmpty() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer ");

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Failure.class, result);
            var failure = (AuthenticationResult.Failure) result;
            assertEquals(401, failure.statusCode());
        }

        @Test
        @DisplayName("should fail when API key is invalid")
        void shouldFailWhenApiKeyIsInvalid() {
            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer invalid-key");

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Failure.class, result);
            var failure = (AuthenticationResult.Failure) result;
            assertEquals(401, failure.statusCode());
            assertTrue(failure.reason().contains("Invalid API key"));
        }

        @Test
        @DisplayName("should succeed with valid API key")
        void shouldSucceedWithValidApiKey() {
            var createResult = apiKeyService
                    .create(
                            "test-key",
                            null,
                            Set.of(
                                    Permission.SERVICE_CONFIG_READ_VALUE,
                                    Permission.SERVICE_CONFIG_CREATE_VALUE,
                                    Permission.SERVICE_CONFIG_UPDATE_VALUE,
                                    Permission.SERVICE_CONFIG_DELETE_VALUE,
                                    "demo-service.admin"),
                            null,
                            "test")
                    .await()
                    .indefinitely();

            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer " + createResult.plaintextKey());

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Success.class, result);
            var success = (AuthenticationResult.Success) result;
            assertEquals("test-key", success.context().principal().name());
            assertTrue(success.context().hasPermission(Permission.SERVICE_CONFIG_READ_VALUE));
            assertTrue(success.context().hasPermission(Permission.SERVICE_CONFIG_CREATE_VALUE));
            assertTrue(success.context().hasPermission(Permission.SERVICE_CONFIG_UPDATE_VALUE));
            assertTrue(success.context().hasPermission(Permission.SERVICE_CONFIG_DELETE_VALUE));
        }

        @Test
        @DisplayName("should fail with revoked API key")
        void shouldFailWithRevokedApiKey() {
            var createResult = apiKeyService
                    .create("revoked-key", null, Set.of(), null, "test")
                    .await()
                    .indefinitely();
            apiKeyService.revoke(createResult.keyId()).await().indefinitely();

            var headers = new MultivaluedHashMap<String, String>();
            headers.putSingle("Authorization", "Bearer " + createResult.plaintextKey());

            var result = provider.authenticate(headers, "/admin/services");

            assertInstanceOf(AuthenticationResult.Failure.class, result);
        }
    }

    @Nested
    @DisplayName("metadata")
    class MetadataTests {

        @Test
        @DisplayName("should have correct name")
        void shouldHaveCorrectName() {
            assertEquals("api-key", provider.name());
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
    }
}
