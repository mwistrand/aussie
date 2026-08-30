package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.httpproblem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.SessionCookieManager;
import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.OidcAuthorizationTransaction;
import aussie.core.model.auth.OidcTokenExchangeResponse;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.OidcRefreshTokenRepository;
import aussie.core.service.auth.OidcTokenExchangeProviderRegistry;
import aussie.core.service.auth.PkceService;
import aussie.core.service.auth.TokenValidationService;
import aussie.spi.OidcTokenExchangeProvider;

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
    private SessionConfig.CookieConfig sessionCookieConfig;

    @Mock
    private OidcTokenExchangeProviderRegistry tokenExchangeRegistry;

    @Mock
    private SessionManagement sessionManagement;

    @Mock
    private OidcRefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenValidationService tokenValidationService;

    @Mock
    private SessionCookieManager cookieManager;

    @Mock
    private OidcConfig.TokenExchangeConfig tokenExchangeConfig;

    @Mock
    private OidcConfig.RefreshTokenConfig refreshTokenConfig;

    @Mock
    private OidcTokenExchangeProvider tokenExchangeProvider;

    private OidcResource resource;

    @BeforeEach
    void setUp() {
        when(oidcConfig.publicEndpointsEnabled()).thenReturn(true);
        Mockito.lenient().when(sessionConfig.cookie()).thenReturn(sessionCookieConfig);
        Mockito.lenient().when(sessionCookieConfig.secure()).thenReturn(false);
        Mockito.lenient().when(cookieManager.responseCookieSameSite()).thenReturn(NewCookie.SameSite.LAX);
        Mockito.lenient()
                .when(cookieManager.createCsrfResponseCookie(any()))
                .thenReturn(new jakarta.ws.rs.core.NewCookie.Builder("aussie_session_csrf")
                        .value("csrf-token")
                        .build());
        resource = new OidcResource(
                pkceService,
                pkceConfig,
                oidcConfig,
                sessionConfig,
                tokenExchangeRegistry,
                sessionManagement,
                refreshTokenRepository,
                tokenValidationService,
                cookieManager);
    }

    @Test
    void rejectsUnvalidatedIdTokenWithoutCreatingSession() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void rejectsIdTokenFromDifferentProviderWithoutCreatingSession() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity("other-idp"))));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void rejectsCryptographicallySubstitutedIdTokenWithoutCreatingSession() {
        setupExchange();
        final var forgedIdToken = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhdHRhY2tlciJ9.";
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse(forgedIdToken)));
        when(tokenValidationService.validate(forgedIdToken))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void allowsExactlyOneConcurrentExchangeOfOneState() throws Exception {
        final var stored = transaction();
        setupExchange(stored);
        final var consumed = new AtomicBoolean();
        when(pkceService.consumeTransaction("state")).thenAnswer(invocation -> Uni.createFrom()
                .item(() -> consumed.compareAndSet(false, true) ? Optional.of(stored) : Optional.empty()));
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity())));
        when(sessionConfig.enabled()).thenReturn(true);
        when(sessionManagement.createSession(any(), eq(null), eq(null)))
                .thenReturn(Uni.createFrom().item(session()));
        when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
        when(refreshTokenConfig.store()).thenReturn(false);
        when(cookieManager.createResponseCookie(any()))
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value("session-1")
                        .build());

        final var start = new CountDownLatch(1);
        final var executor = Executors.newFixedThreadPool(2);
        try {
            final var attempts = new AtomicInteger();
            final Callable<Void> exchange = () -> {
                start.await();
                try {
                    resource.exchangeToken("code", "verifier", "state", "https://app.example.com/callback", "state")
                            .await()
                            .atMost(Duration.ofSeconds(5));
                    attempts.incrementAndGet();
                } catch (HttpProblem ignored) {
                    // The second consumer must receive the normal invalid/replayed-state problem.
                }
                return null;
            };
            final var first = executor.submit(exchange);
            final var second = executor.submit(exchange);
            start.countDown();
            first.get();
            second.get();

            assertEquals(1, attempts.get());
            verify(sessionManagement).createSession(any(), eq(null), eq(null));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createsSessionFromValidatorProvenance() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        final var identity = identity();
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));
        final var session = session();
        when(sessionManagement.createSession(identity, null, null))
                .thenReturn(Uni.createFrom().item(session));
        when(sessionConfig.enabled()).thenReturn(true);
        when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
        when(refreshTokenConfig.store()).thenReturn(false);
        when(cookieManager.createResponseCookie(session))
                .thenReturn(new jakarta.ws.rs.core.NewCookie.Builder("aussie_session")
                        .value("session-1")
                        .httpOnly(true)
                        .build());

        final var result = resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), result.getStatus());
        assertNull(result.getEntity());
        assertEquals("session-1", result.getCookies().get("aussie_session").getValue());
        assertEquals(0, result.getCookies().get("aussie_oidc_state").getMaxAge());
        verify(sessionManagement).createSession(identity, null, null);
    }

    @Test
    void consumesStateAndRejectsRedirectSubstitution() {
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isValidState("state")).thenReturn(true);
        when(pkceService.isValidCodeVerifier("verifier")).thenReturn(true);
        when(pkceService.consumeTransaction("state"))
                .thenReturn(Uni.createFrom().item(Optional.of(transaction())));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://attacker.example/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
        verify(tokenExchangeProvider, never()).exchange(any());
    }

    @Test
    void rejectsIdTokenWithWrongNonce() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        final var identity = identity()
                .withClaims(Map.of(
                        "sub",
                        "user-1",
                        "nonce",
                        "wrong-nonce",
                        "iat",
                        Instant.now().getEpochSecond(),
                        "exp",
                        Instant.now().plusSeconds(3600).getEpochSecond()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void rejectsMalformedIdTokenAuthenticationTime() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        final var identity = identity()
                .withClaims(Map.of(
                        "sub",
                        "user-1",
                        "nonce",
                        "nonce",
                        "iat",
                        Instant.now().getEpochSecond(),
                        "auth_time",
                        "not-a-time",
                        "exp",
                        Instant.now().plusSeconds(3600).getEpochSecond()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void rejectsAuthorizationWhenPublicHelpersAreDisabled() {
        when(oidcConfig.publicEndpointsEnabled()).thenReturn(false);

        final var problem = assertThrows(
                HttpProblem.class,
                () -> resource.authorize("https://app.example.com/callback", "challenge", "S256", null));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
    }

    @Test
    void storesServerPkceStateAndBindsInitiatingBrowser() {
        when(sessionCookieConfig.secure()).thenReturn(true);
        when(cookieManager.responseCookieSameSite()).thenReturn(NewCookie.SameSite.NONE);
        when(pkceConfig.enabled()).thenReturn(true);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(pkceService.isValidChallengeMethod("S256")).thenReturn(true);
        when(pkceService.isValidCodeChallenge("challenge")).thenReturn(true);
        when(pkceService.isValidState("server-state")).thenReturn(true);
        when(pkceService.generateNonce()).thenReturn("generated-nonce");
        when(pkceConfig.challengeTtl()).thenReturn(Duration.ofMinutes(10));
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.redirectUris()).thenReturn(Optional.of(Set.of("https://app.example.com/callback")));
        when(tokenExchangeConfig.authorizationEndpoint()).thenReturn(Optional.of("https://idp.example.com/authorize"));
        when(tokenExchangeConfig.providerId()).thenReturn(Optional.of("configured-idp"));
        when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("client"));
        when(tokenExchangeConfig.scopes()).thenReturn(Set.of("openid"));
        when(pkceService.generateState()).thenReturn("server-state");
        when(pkceService.storeTransaction(eq("server-state"), any()))
                .thenReturn(Uni.createFrom().voidItem());

        final var response = resource.authorize("https://app.example.com/callback", "challenge", "S256", null)
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().contains("state=server-state"));
        assertTrue(response.getLocation().toString().contains("nonce=generated-nonce"));
        verify(pkceService).storeTransaction(eq("server-state"), any());
        final var cookie = response.getCookies().get("aussie_oidc_state");
        assertEquals("server-state", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals(NewCookie.SameSite.NONE, cookie.getSameSite());
        assertEquals(600, cookie.getMaxAge());
    }

    @Test
    void rejectsCallerSuppliedState() {
        when(pkceConfig.enabled()).thenReturn(true);
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);

        final var problem = assertThrows(
                HttpProblem.class,
                () -> resource.authorize("https://app.example.com/callback", "challenge", "S256", "attacker-state"));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
        verify(pkceService, never()).storeTransaction(any(), any());
    }

    @Test
    void rejectsExchangeFromDifferentBrowserBeforeConsumingState() {
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isValidState("state")).thenReturn(true);
        when(pkceService.isValidState("other-state")).thenReturn(true);
        when(pkceService.isValidCodeVerifier("verifier")).thenReturn(true);

        final var problem = assertThrows(
                HttpProblem.class, () -> resource.exchangeToken("code", "verifier", "state", null, "other-state"));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
        verify(pkceService, never()).consumeTransaction(any());
        verify(tokenExchangeProvider, never()).exchange(any());
    }

    @Test
    void rejectsMissingIdTokenForPublicOidcClient() {
        setupExchange(transaction(OidcAuthorizationTransaction.ClientType.PUBLIC));
        final var response = new OidcTokenExchangeResponse(
                "access-token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(response));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", "verifier", "state", "https://app.example.com/callback", "state")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.BAD_GATEWAY.getStatusCode(), problem.getStatusCode());
        verify(tokenValidationService, never()).validate(any());
    }

    @Test
    void returnsTokensOnlyAfterValidatingPublicClientIdToken() {
        setupExchange(transaction(OidcAuthorizationTransaction.ClientType.PUBLIC));
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity())));

        final var result = resource.exchangeToken("code", "verifier", "state", null, "state")
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), result.getStatus());
        final var body = assertInstanceOf(Map.class, result.getEntity());
        assertEquals("access-token", body.get("access_token"));
        assertEquals("id-token", body.get("id_token"));
        assertEquals(0, result.getCookies().get("aussie_oidc_state").getMaxAge());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void rejectsMalformedVerifierBeforeConsumingState() {
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isValidState("state")).thenReturn(true);
        when(pkceService.isValidCodeVerifier("short")).thenReturn(false);

        final var problem =
                assertThrows(HttpProblem.class, () -> resource.exchangeToken("code", "short", "state", null, "state"));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), problem.getStatusCode());
        verify(pkceService, never()).consumeTransaction(any());
    }

    @Test
    void rejectsDisabledTokenExchangeBeforeConsumingState() {
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(false);

        final var problem = assertThrows(
                HttpProblem.class, () -> resource.exchangeToken("code", "verifier", "state", null, "state"));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
        verify(pkceService, never()).consumeTransaction(any());
    }

    private void setupExchange() {
        setupExchange(transaction());
    }

    private void setupExchange(OidcAuthorizationTransaction transaction) {
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isValidState("state")).thenReturn(true);
        when(pkceService.isValidCodeVerifier("verifier")).thenReturn(true);
        when(pkceService.consumeTransaction("state"))
                .thenReturn(Uni.createFrom().item(Optional.of(transaction)));
        when(pkceService.verifyChallenge(transaction, "verifier")).thenReturn(true);
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
        when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("client"));
        when(tokenExchangeConfig.clientSecret()).thenReturn(Optional.empty());
        when(tokenExchangeConfig.clientAuthMethod()).thenReturn("client_secret_basic");
        when(tokenExchangeConfig.scopes()).thenReturn(Set.of("openid"));
        when(tokenExchangeRegistry.getProvider()).thenReturn(tokenExchangeProvider);
    }

    private OidcTokenExchangeResponse tokenResponse() {
        return tokenResponse("id-token");
    }

    private OidcTokenExchangeResponse tokenResponse(String idToken) {
        return new OidcTokenExchangeResponse(
                "access-token", Optional.of(idToken), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
    }

    private ValidatedIdentity identity() {
        return identity("configured-idp");
    }

    private ValidatedIdentity identity(String providerId) {
        final var expiresAt = Instant.now().plusSeconds(3600);
        return ValidatedIdentity.fromValidatedClaims(
                providerId,
                "user-1",
                "https://idp.example.com",
                Set.of("client"),
                Optional.empty(),
                Optional.of("token-1"),
                Map.of(
                        "sub",
                        "user-1",
                        "nonce",
                        "nonce",
                        "iat",
                        Instant.now().getEpochSecond(),
                        "exp",
                        expiresAt.getEpochSecond()),
                Optional.empty(),
                expiresAt);
    }

    private Session session() {
        final var now = Instant.now();
        return new Session(
                "session-1",
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1"),
                Set.of(),
                now,
                now.plusSeconds(3600),
                now,
                null,
                null);
    }

    private OidcAuthorizationTransaction transaction() {
        return transaction(OidcAuthorizationTransaction.ClientType.SESSION);
    }

    private OidcAuthorizationTransaction transaction(OidcAuthorizationTransaction.ClientType clientType) {
        final var now = Instant.now();
        return new OidcAuthorizationTransaction(
                "configured-idp",
                "https://app.example.com/callback",
                "challenge",
                "nonce",
                clientType,
                now,
                now.plusSeconds(600));
    }
}
