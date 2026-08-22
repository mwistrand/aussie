package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.OidcRefreshTokenRepository;
import aussie.core.service.auth.OidcTokenExchangeProviderRegistry;
import aussie.core.service.auth.PkceService;

@DisplayName("OidcResource")
@ExtendWith(MockitoExtension.class)
class OidcResourceTest {

    @Mock
    private PkceService pkceService;

    @Mock
    private PkceConfig pkceConfig;

    @Mock
    private OidcConfig oidcConfig;

    @Mock
    private SessionConfig sessionConfig;

    @Mock
    private OidcTokenExchangeProviderRegistry tokenExchangeRegistry;

    @Mock
    private SessionManagement sessionManagement;

    @Mock
    private OidcRefreshTokenRepository refreshTokenRepository;

    private OidcResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(oidcConfig.publicEndpointsEnabled()).thenReturn(true);
        resource = new OidcResource(
                pkceService,
                pkceConfig,
                oidcConfig,
                sessionConfig,
                tokenExchangeRegistry,
                sessionManagement,
                refreshTokenRepository);
    }

    @Nested
    @DisplayName("authorize")
    class Authorize {

        @Test
        @DisplayName("throws featureDisabled when public OIDC helpers are disabled")
        void throwsFeatureDisabledWhenPublicHelpersDisabled() {
            when(oidcConfig.publicEndpointsEnabled()).thenReturn(false);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback",
                            "challenge",
                            "S256",
                            null,
                            "https://idp.example.com/auth"));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws featureDisabled when PKCE is disabled")
        void throwsFeatureDisabledWhenPkceDisabled() {
            when(pkceConfig.enabled()).thenReturn(false);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback",
                            "challenge",
                            "S256",
                            null,
                            "https://idp.example.com/auth"));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when redirect_uri is missing")
        void throwsBadRequestWhenRedirectUriMissing() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(null, "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when redirect_uri is blank")
        void throwsBadRequestWhenRedirectUriBlank() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize("  ", "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when redirect_uri has no scheme")
        void throwsBadRequestWhenRedirectUriNoScheme() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "example.com/callback", "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when redirect_uri has invalid scheme")
        void throwsBadRequestWhenRedirectUriInvalidScheme() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "ftp://example.com/callback", "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when idp_url is missing")
        void throwsBadRequestWhenIdpUrlMissing() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize("https://app.example.com/callback", "challenge", "S256", null, null));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when PKCE required but no code_challenge")
        void throwsBadRequestWhenPkceRequiredButNoChallengeProvided() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback", null, "S256", null, "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when challenge method is invalid")
        void throwsBadRequestWhenInvalidChallengeMethod() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);
            when(pkceService.isValidChallengeMethod("plain")).thenReturn(false);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback",
                            "challenge",
                            "plain",
                            null,
                            "https://idp.example.com/auth"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("redirects to IdP URL on successful authorization")
        void redirectsToIdpOnSuccess() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);
            when(pkceService.isValidChallengeMethod("S256")).thenReturn(true);
            when(pkceService.generateState()).thenReturn("generated-state");
            when(pkceService.storeChallenge(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().voidItem());

            final var response = resource.authorize(
                            "https://app.example.com/callback",
                            "challenge123",
                            "S256",
                            null,
                            "https://idp.example.com/auth")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            final var location = response.getLocation().toString();
            assertTrue(location.startsWith("https://idp.example.com/auth?"));
            assertTrue(location.contains("state=generated-state"));
            assertTrue(location.contains("redirect_uri="));
        }

        @Test
        @DisplayName("includes client_state in redirect URL when provided")
        void includesClientStateInRedirectUrl() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(pkceService.generateState()).thenReturn("generated-state");

            final var response = resource.authorize(
                            "https://app.example.com/callback",
                            null,
                            null,
                            "my-csrf-token",
                            "https://idp.example.com/auth")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            final var location = response.getLocation().toString();
            assertTrue(location.contains("client_state=my-csrf-token"));
        }
    }

    @Nested
    @DisplayName("exchangeToken")
    class ExchangeToken {

        @Test
        @DisplayName("throws featureDisabled when PKCE is disabled")
        void throwsFeatureDisabledWhenPkceDisabled() {
            when(pkceConfig.enabled()).thenReturn(false);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("code", "verifier", "state", "https://app.example.com/callback"));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when code is missing")
        void throwsBadRequestWhenCodeMissing() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken(null, "verifier", "state", "https://app.example.com/callback"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when state is missing")
        void throwsBadRequestWhenStateMissing() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("code", "verifier", null, "https://app.example.com/callback"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when PKCE required but no verifier")
        void throwsBadRequestWhenPkceRequiredButNoVerifier() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("code", null, "state", "https://app.example.com/callback"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when PKCE verification fails")
        void throwsBadRequestWhenPkceVerificationFails() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);
            when(pkceService.verifyChallenge("state-1", "bad-verifier"))
                    .thenReturn(Uni.createFrom().item(false));

            final var ex = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                            "code", "bad-verifier", "state-1", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("parseIdTokenClaims")
    class ParseIdTokenClaims {

        @Test
        @DisplayName("extracts claims from a valid JWT")
        void extractsClaimsFromValidJwt() throws Exception {
            // Use reflection to access the private method
            final var method = OidcResource.class.getDeclaredMethod("parseIdTokenClaims", String.class);
            method.setAccessible(true);

            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}"
                            .getBytes(StandardCharsets.UTF_8));
            final var signature = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("fake-signature".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            @SuppressWarnings("unchecked")
            final var claims = (Map<String, Object>) method.invoke(resource, jwt);

            assertEquals("user-1", claims.get("sub"));
            assertEquals("https://idp.example.com", claims.get("iss"));
        }

        @Test
        @DisplayName("returns empty map for invalid JWT format (not 3 parts)")
        void returnsEmptyMapForInvalidFormat() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseIdTokenClaims", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            final var claims = (Map<String, Object>) method.invoke(resource, "not.a-jwt");

            assertTrue(claims.isEmpty());
        }
    }

    @Nested
    @DisplayName("extractPermissions")
    class ExtractPermissions {

        @Test
        @DisplayName("extracts permissions from a collection claim")
        @SuppressWarnings("unchecked")
        void extractsFromCollectionClaim() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("extractPermissions", Map.class);
            method.setAccessible(true);

            final var claims = Map.<String, Object>of("roles", List.of("admin", "user"));
            final var permissions = (Set<String>) method.invoke(resource, claims);

            assertEquals(Set.of("admin", "user"), permissions);
        }

        @Test
        @DisplayName("extracts permissions from a string claim by splitting")
        @SuppressWarnings("unchecked")
        void extractsFromStringClaim() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("extractPermissions", Map.class);
            method.setAccessible(true);

            final var claims = Map.<String, Object>of("scope", "read write admin");
            final var permissions = (Set<String>) method.invoke(resource, claims);

            assertTrue(permissions.contains("read"));
            assertTrue(permissions.contains("write"));
            assertTrue(permissions.contains("admin"));
        }

        @Test
        @DisplayName("returns empty set when no permission claims exist")
        @SuppressWarnings("unchecked")
        void returnsEmptyWhenNoPermissionClaims() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("extractPermissions", Map.class);
            method.setAccessible(true);

            final var claims = Map.<String, Object>of("sub", "user-1", "iss", "issuer");
            final var permissions = (Set<String>) method.invoke(resource, claims);

            assertTrue(permissions.isEmpty());
        }
    }

    @Nested
    @DisplayName("parseClientAuthMethod")
    class ParseClientAuthMethod {

        @Test
        @DisplayName("returns CLIENT_SECRET_POST for client_secret_post")
        void returnsClientSecretPost() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseClientAuthMethod", String.class);
            method.setAccessible(true);

            final var result = method.invoke(resource, "client_secret_post");

            assertEquals(aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod.CLIENT_SECRET_POST, result);
        }

        @Test
        @DisplayName("returns CLIENT_SECRET_BASIC by default")
        void returnsClientSecretBasicByDefault() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseClientAuthMethod", String.class);
            method.setAccessible(true);

            final var result = method.invoke(resource, "client_secret_basic");

            assertEquals(aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod.CLIENT_SECRET_BASIC, result);
        }

        @Test
        @DisplayName("returns CLIENT_SECRET_BASIC for unknown method")
        void returnsClientSecretBasicForUnknown() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseClientAuthMethod", String.class);
            method.setAccessible(true);

            final var result = method.invoke(resource, "unknown_method");

            assertEquals(aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod.CLIENT_SECRET_BASIC, result);
        }
    }

    @Nested
    @DisplayName("buildIdpUrl")
    class BuildIdpUrl {

        @Test
        @DisplayName("appends state and redirect_uri with ? when URL has no query params")
        void appendsWithQuestionMark() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod(
                    "buildIdpUrl", String.class, String.class, String.class, String.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(
                    resource, "https://idp.example.com/auth", "state-123", "https://app.example.com/callback", null);

            assertTrue(result.startsWith("https://idp.example.com/auth?"));
            assertTrue(result.contains("state=state-123"));
            assertTrue(result.contains("redirect_uri="));
        }

        @Test
        @DisplayName("appends state and redirect_uri with & when URL already has query params")
        void appendsWithAmpersand() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod(
                    "buildIdpUrl", String.class, String.class, String.class, String.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(
                    resource,
                    "https://idp.example.com/auth?response_type=code",
                    "state-123",
                    "https://app.example.com/callback",
                    null);

            assertTrue(result.startsWith("https://idp.example.com/auth?response_type=code&"));
            assertTrue(result.contains("state=state-123"));
        }

        @Test
        @DisplayName("includes client_state when provided")
        void includesClientState() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod(
                    "buildIdpUrl", String.class, String.class, String.class, String.class);
            method.setAccessible(true);

            final var result = (String) method.invoke(
                    resource,
                    "https://idp.example.com/auth",
                    "state-123",
                    "https://app.example.com/callback",
                    "csrf-token");

            assertTrue(result.contains("client_state=csrf-token"));
        }
    }

    @Nested
    @DisplayName("validateUrl")
    class ValidateUrl {

        @Test
        @DisplayName("accepts valid https URL")
        void acceptsValidHttpsUrl() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("validateUrl", String.class, String.class);
            method.setAccessible(true);

            // Should not throw
            method.invoke(resource, "https://example.com/callback", "redirect_uri");
        }

        @Test
        @DisplayName("accepts valid http URL")
        void acceptsValidHttpUrl() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("validateUrl", String.class, String.class);
            method.setAccessible(true);

            // Should not throw
            method.invoke(resource, "http://localhost:3000/callback", "redirect_uri");
        }

        @Test
        @DisplayName("rejects URL with invalid scheme")
        void rejectsInvalidScheme() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("validateUrl", String.class, String.class);
            method.setAccessible(true);

            try {
                method.invoke(resource, "ftp://example.com/file", "redirect_uri");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof HttpProblem);
                assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ((HttpProblem) e.getCause()).getStatusCode());
                return;
            }
            throw new AssertionError("Expected HttpProblem to be thrown");
        }

        @Test
        @DisplayName("rejects URL without host")
        void rejectsUrlWithoutHost() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("validateUrl", String.class, String.class);
            method.setAccessible(true);

            try {
                method.invoke(resource, "http:///path", "redirect_uri");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof HttpProblem);
                assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ((HttpProblem) e.getCause()).getStatusCode());
                return;
            }
            throw new AssertionError("Expected HttpProblem to be thrown");
        }
    }
}
