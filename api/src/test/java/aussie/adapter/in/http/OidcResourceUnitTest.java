package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.OidcRefreshTokenRepository;
import aussie.core.service.auth.OidcTokenExchangeProviderRegistry;
import aussie.core.service.auth.PkceService;
import aussie.spi.OidcTokenExchangeProvider;

@DisplayName("OidcResource unit tests")
@ExtendWith(MockitoExtension.class)
class OidcResourceUnitTest {

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

    @Mock
    private OidcConfig.TokenExchangeConfig tokenExchangeConfig;

    @Mock
    private OidcConfig.RefreshTokenConfig refreshTokenConfig;

    @Mock
    private OidcTokenExchangeProvider tokenExchangeProvider;

    private OidcResource resource;

    @BeforeEach
    void setUp() {
        resource = new OidcResource(
                pkceService,
                pkceConfig,
                oidcConfig,
                sessionConfig,
                tokenExchangeRegistry,
                sessionManagement,
                refreshTokenRepository);
    }

    private String buildJwt(String payloadJson) {
        final var header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        final var payload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        final var signature =
                Base64.getUrlEncoder().withoutPadding().encodeToString("fake-sig".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }

    private void setupTokenExchangeConfig() {
        lenient().when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        lenient().when(tokenExchangeConfig.enabled()).thenReturn(true);
        lenient().when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
        lenient().when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("my-client-id"));
        lenient().when(tokenExchangeConfig.clientSecret()).thenReturn(Optional.of("my-secret"));
        lenient().when(tokenExchangeConfig.clientAuthMethod()).thenReturn("client_secret_basic");
        lenient().when(tokenExchangeConfig.scopes()).thenReturn(Set.of("openid", "profile"));
        lenient().when(tokenExchangeRegistry.getProvider()).thenReturn(tokenExchangeProvider);
    }

    private Session createSession(String id, String userId) {
        final var now = Instant.now();
        return new Session(
                id,
                userId,
                "https://idp.example.com",
                Map.of("sub", userId),
                Set.of("user"),
                now,
                now.plusSeconds(3600),
                now,
                "TestAgent",
                "127.0.0.1");
    }

    @Nested
    @DisplayName("authorize")
    class Authorize {

        @Test
        @DisplayName("should throw bad request when redirect_uri has blank host")
        void shouldThrowBadRequestWhenRedirectUriHasBlankHost() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "http:///path", "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when idp_url is blank")
        void shouldThrowBadRequestWhenIdpUrlBlank() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize("https://app.example.com/callback", "challenge", "S256", null, "  "));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when idp_url has invalid scheme")
        void shouldThrowBadRequestWhenIdpUrlInvalidScheme() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback",
                            "challenge",
                            "S256",
                            null,
                            "ftp://idp.example.com/auth"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when idp_url has no host")
        void shouldThrowBadRequestWhenIdpUrlNoHost() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback", "challenge", "S256", null, "http:///path"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when PKCE required and code_challenge is blank")
        void shouldThrowBadRequestWhenPkceRequiredAndCodeChallengeBlank() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "https://app.example.com/callback", "  ", "S256", null, "https://idp.example.com/auth"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should skip PKCE validation when not required and no challenge provided")
        void shouldSkipPkceValidationWhenNotRequiredAndNoChallengeProvided() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(pkceService.generateState()).thenReturn("state-1");

            final var response = resource.authorize(
                            "https://app.example.com/callback", null, null, null, "https://idp.example.com/auth")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
        }

        @Test
        @DisplayName("should store challenge when code_challenge provided but PKCE not required")
        void shouldStoreChallengeWhenProvidedButPkceNotRequired() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(pkceService.generateState()).thenReturn("state-1");
            when(pkceService.storeChallenge("state-1", "my-challenge"))
                    .thenReturn(Uni.createFrom().voidItem());

            final var response = resource.authorize(
                            "https://app.example.com/callback",
                            "my-challenge",
                            "S256",
                            null,
                            "https://idp.example.com/auth")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            verify(pkceService).storeChallenge("state-1", "my-challenge");
        }

        @Test
        @DisplayName("should append & when idp_url already has query params")
        void shouldAppendAmpersandWhenIdpUrlHasQueryParams() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(pkceService.generateState()).thenReturn("state-1");

            final var response = resource.authorize(
                            "https://app.example.com/callback",
                            null,
                            null,
                            null,
                            "https://idp.example.com/auth?response_type=code")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            final var location = response.getLocation().toString();
            assertTrue(location.contains("response_type=code&state=state-1"));
        }

        @Test
        @DisplayName("should not include client_state when blank")
        void shouldNotIncludeClientStateWhenBlank() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(pkceService.generateState()).thenReturn("state-1");

            final var response = resource.authorize(
                            "https://app.example.com/callback", null, null, "  ", "https://idp.example.com/auth")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            final var location = response.getLocation().toString();
            assertFalse(location.contains("client_state"));
        }

        @Test
        @DisplayName("should throw bad request when redirect_uri is not a valid URL")
        void shouldThrowBadRequestWhenRedirectUriInvalidUrl() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "not a valid url with spaces[]",
                            "challenge",
                            "S256",
                            null,
                            "https://idp.example.com/auth"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when redirect_uri has no scheme")
        void shouldThrowBadRequestWhenRedirectUriNoScheme() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authorize(
                            "example.com/callback", "challenge", "S256", null, "https://idp.example.com/auth"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }

    @Nested
    @DisplayName("exchangeToken")
    class ExchangeToken {

        @Test
        @DisplayName("should throw bad request when code is blank")
        void shouldThrowBadRequestWhenCodeBlank() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("  ", "verifier", "state", "https://app.example.com/callback"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when state is blank")
        void shouldThrowBadRequestWhenStateBlank() {
            when(pkceConfig.enabled()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("code", "verifier", "  ", "https://app.example.com/callback"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw bad request when PKCE required and verifier is blank")
        void shouldThrowBadRequestWhenPkceRequiredAndVerifierBlank() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.exchangeToken("code", "  ", "state", "https://app.example.com/callback"));

            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should proceed without verifier when PKCE not required and verifier is null")
        void shouldProceedWithoutVerifierWhenPkceNotRequired() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should proceed without verifier when PKCE not required and verifier is blank")
        void shouldProceedWithoutVerifierWhenPkceNotRequiredAndVerifierBlank() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", "  ", "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should complete token exchange with valid verifier")
        void shouldCompleteTokenExchangeWithValidVerifier() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(true);
            when(pkceService.verifyChallenge("state-1", "valid-verifier"))
                    .thenReturn(Uni.createFrom().item(true));
            setupTokenExchangeConfig();
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token",
                    Optional.of("id-token"),
                    Optional.empty(),
                    "Bearer",
                    3600,
                    Optional.of("openid profile"),
                    Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken(
                            "code-123", "valid-verifier", "state-1", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("access-token", body.get("access_token"));
            assertEquals("id-token", body.get("id_token"));
            assertEquals("openid profile", body.get("scope"));
        }

        @Test
        @DisplayName("should throw feature disabled when token exchange disabled")
        void shouldThrowFeatureDisabledWhenTokenExchangeDisabled() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
            when(tokenExchangeConfig.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                            "code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5)));

            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw internal error when token endpoint not configured")
        void shouldThrowInternalErrorWhenTokenEndpointNotConfigured() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
            when(tokenExchangeConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.empty());

            final var ex = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                            "code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5)));

            assertEquals(
                    Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should throw internal error when client ID not configured")
        void shouldThrowInternalErrorWhenClientIdNotConfigured() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
            when(tokenExchangeConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
            when(tokenExchangeConfig.clientId()).thenReturn(Optional.empty());

            final var ex = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                            "code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5)));

            assertEquals(
                    Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("should handle empty scopes")
        void shouldHandleEmptyScopes() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
            when(tokenExchangeConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
            when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("my-client-id"));
            when(tokenExchangeConfig.clientSecret()).thenReturn(Optional.empty());
            when(tokenExchangeConfig.clientAuthMethod()).thenReturn("client_secret_basic");
            when(tokenExchangeConfig.scopes()).thenReturn(Set.of());
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);
            when(tokenExchangeRegistry.getProvider()).thenReturn(tokenExchangeProvider);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should use client_secret_post auth method")
        void shouldUseClientSecretPostAuthMethod() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
            when(tokenExchangeConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
            when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("my-client-id"));
            when(tokenExchangeConfig.clientSecret()).thenReturn(Optional.of("secret"));
            when(tokenExchangeConfig.clientAuthMethod()).thenReturn("client-secret-post");
            when(tokenExchangeConfig.scopes()).thenReturn(Set.of("openid"));
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);
            when(tokenExchangeRegistry.getProvider()).thenReturn(tokenExchangeProvider);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }
    }

    @Nested
    @DisplayName("handleTokenResponse - session creation")
    class HandleTokenResponse {

        @Test
        @DisplayName("should create session when conditions are met")
        void shouldCreateSessionWhenConditionsAreMet() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);
            when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
            when(refreshTokenConfig.store()).thenReturn(false);

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(session));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("session-abc", body.get("session_id"));
        }

        @Test
        @DisplayName("should store refresh token when configured and present")
        void shouldStoreRefreshTokenWhenConfiguredAndPresent() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);
            when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
            when(refreshTokenConfig.store()).thenReturn(true);
            when(refreshTokenConfig.defaultTtl()).thenReturn(Duration.ofHours(168));

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token",
                    Optional.of(idToken),
                    Optional.of("refresh-token-value"),
                    "Bearer",
                    3600,
                    Optional.empty(),
                    Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(session));
            when(refreshTokenRepository.store(eq("session-abc"), eq("refresh-token-value"), any()))
                    .thenReturn(Uni.createFrom().voidItem());

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(refreshTokenRepository).store(eq("session-abc"), eq("refresh-token-value"), any());
        }

        @Test
        @DisplayName("should not create session when session config disabled")
        void shouldNotCreateSessionWhenSessionConfigDisabled() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(false);
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(true);

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement, never()).createSession(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should not create session when createSession config is false")
        void shouldNotCreateSessionWhenCreateSessionConfigFalse() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement, never()).createSession(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should not create session when no id token present")
        void shouldNotCreateSessionWhenNoIdTokenPresent() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement, never()).createSession(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("should not store refresh token when store disabled")
        void shouldNotStoreRefreshTokenWhenStoreDisabled() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);
            when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
            when(refreshTokenConfig.store()).thenReturn(false);

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token",
                    Optional.of(idToken),
                    Optional.of("refresh-token"),
                    "Bearer",
                    3600,
                    Optional.empty(),
                    Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(session));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(refreshTokenRepository, never()).store(any(), any(), any());
        }

        @Test
        @DisplayName("should not store refresh token when store enabled but no refresh token present")
        void shouldNotStoreRefreshTokenWhenNotPresent() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);
            when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
            when(refreshTokenConfig.store()).thenReturn(true);

            final var idToken = buildJwt("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(session));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(refreshTokenRepository, never()).store(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("buildTokenResponse")
    class BuildTokenResponse {

        @Test
        @DisplayName("should include id_token and scope when present")
        void shouldIncludeIdTokenAndScopeWhenPresent() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token",
                    Optional.of("my-id-token"),
                    Optional.empty(),
                    "Bearer",
                    7200,
                    Optional.of("openid profile"),
                    Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("my-id-token", body.get("id_token"));
            assertEquals("openid profile", body.get("scope"));
            assertEquals("Bearer", body.get("token_type"));
            assertEquals(7200L, body.get("expires_in"));
        }

        @Test
        @DisplayName("should omit id_token and scope when not present")
        void shouldOmitIdTokenAndScopeWhenNotPresent() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            lenient().when(tokenExchangeConfig.createSession()).thenReturn(false);

            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertFalse(body.containsKey("id_token"));
            assertFalse(body.containsKey("scope"));
            assertFalse(body.containsKey("session_id"));
        }
    }

    @Nested
    @DisplayName("parseIdTokenClaims")
    class ParseIdTokenClaims {

        @Test
        @DisplayName("should return empty map on exception during parsing")
        void shouldReturnEmptyMapOnException() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseIdTokenClaims", String.class);
            method.setAccessible(true);

            // Build a JWT with invalid base64 payload
            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            // Payload that is valid base64 but not valid JSON
            final var payload =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("not-json".getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            @SuppressWarnings("unchecked")
            final var claims = (Map<String, Object>) method.invoke(resource, jwt);

            assertTrue(claims.isEmpty());
        }

        @Test
        @DisplayName("should use defaults when sub and iss not in claims")
        void shouldUseDefaultsWhenSubAndIssNotInClaims() {
            when(pkceConfig.enabled()).thenReturn(true);
            when(pkceService.isRequired()).thenReturn(false);
            setupTokenExchangeConfig();
            when(sessionConfig.enabled()).thenReturn(true);
            when(tokenExchangeConfig.createSession()).thenReturn(true);
            when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
            when(refreshTokenConfig.store()).thenReturn(false);

            // JWT with no sub or iss
            final var idToken = buildJwt("{\"email\":\"user@example.com\"}");
            final var tokenResponse = new OidcTokenExchangeResponse(
                    "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            when(tokenExchangeProvider.exchange(any()))
                    .thenReturn(Uni.createFrom().item(tokenResponse));

            final var session = createSession("session-abc", "unknown");
            when(sessionManagement.createSession(eq("unknown"), eq("unknown"), any(), any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(session));

            final var response = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement).createSession(eq("unknown"), eq("unknown"), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("extractPermissions")
    class ExtractPermissions {

        @SuppressWarnings("unchecked")
        private Set<String> extractPermissions(Map<String, Object> claims) throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("extractPermissions", Map.class);
            method.setAccessible(true);
            return (Set<String>) method.invoke(resource, claims);
        }

        @Test
        @DisplayName("should extract from 'groups' collection claim")
        void shouldExtractFromGroupsCollectionClaim() throws Exception {
            final var claims = Map.<String, Object>of("groups", List.of("admins", "editors"));
            final var permissions = extractPermissions(claims);

            assertEquals(Set.of("admins", "editors"), permissions);
        }

        @Test
        @DisplayName("should extract from 'permissions' collection claim")
        void shouldExtractFromPermissionsCollectionClaim() throws Exception {
            final var claims = Map.<String, Object>of("permissions", List.of("read", "write"));
            final var permissions = extractPermissions(claims);

            assertEquals(Set.of("read", "write"), permissions);
        }

        @Test
        @DisplayName("should split string claim with commas")
        void shouldSplitStringClaimWithCommas() throws Exception {
            final var claims = Map.<String, Object>of("roles", "admin,user,editor");
            final var permissions = extractPermissions(claims);

            assertTrue(permissions.contains("admin"));
            assertTrue(permissions.contains("user"));
            assertTrue(permissions.contains("editor"));
        }

        @Test
        @DisplayName("should skip non-collection non-string values")
        void shouldSkipNonCollectionNonStringValues() throws Exception {
            final var claims = Map.<String, Object>of("roles", 42, "sub", "user-1");
            final var permissions = extractPermissions(claims);

            assertTrue(permissions.isEmpty());
        }
    }

    @Nested
    @DisplayName("parseClientAuthMethod")
    class ParseClientAuthMethod {

        @Test
        @DisplayName("should handle hyphenated client-secret-post")
        void shouldHandleHyphenatedClientSecretPost() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseClientAuthMethod", String.class);
            method.setAccessible(true);

            final var result = method.invoke(resource, "client-secret-post");

            assertEquals(aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod.CLIENT_SECRET_POST, result);
        }

        @Test
        @DisplayName("should handle mixed case CLIENT_SECRET_POST")
        void shouldHandleMixedCaseClientSecretPost() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("parseClientAuthMethod", String.class);
            method.setAccessible(true);

            final var result = method.invoke(resource, "Client_Secret_Post");

            assertEquals(aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod.CLIENT_SECRET_POST, result);
        }
    }

    @Nested
    @DisplayName("validateUrl")
    class ValidateUrl {

        @Test
        @DisplayName("should throw bad request for malformed URL causing IllegalArgumentException")
        void shouldThrowBadRequestForMalformedUrl() throws Exception {
            final var method = OidcResource.class.getDeclaredMethod("validateUrl", String.class, String.class);
            method.setAccessible(true);

            final var ex = assertThrows(
                    InvocationTargetException.class, () -> method.invoke(resource, "ht tp://bad url[", "redirect_uri"));
            final var problem = assertInstanceOf(HttpProblem.class, ex.getCause());
            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(),
                    problem.getStatus().getStatusCode());
            assertTrue(problem.getDetail().contains("not a valid URL"));
        }
    }
}
