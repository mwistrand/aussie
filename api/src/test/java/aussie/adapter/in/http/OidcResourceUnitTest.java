package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.OidcConfig;
import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
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
    private OidcTokenExchangeProviderRegistry tokenExchangeRegistry;

    @Mock
    private SessionManagement sessionManagement;

    @Mock
    private OidcRefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenValidationService tokenValidationService;

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
        resource = new OidcResource(
                pkceService,
                pkceConfig,
                oidcConfig,
                sessionConfig,
                tokenExchangeRegistry,
                sessionManagement,
                refreshTokenRepository,
                tokenValidationService);
    }

    @Test
    void rejectsUnvalidatedIdTokenWithoutCreatingSession() {
        setupExchange();
        when(tokenExchangeProvider.exchange(any())).thenReturn(Uni.createFrom().item(tokenResponse()));
        when(tokenValidationService.validate("id-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

        final var problem = assertThrows(HttpProblem.class, () -> resource.exchangeToken(
                        "code", null, "state", "https://app.example.com/callback")
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
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
        when(tokenExchangeConfig.refreshToken()).thenReturn(refreshTokenConfig);
        when(refreshTokenConfig.store()).thenReturn(false);

        final var result = resource.exchangeToken("code", null, "state", "https://app.example.com/callback")
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), result.getStatus());
        verify(sessionManagement).createSession(identity, null, null);
    }

    @Test
    void rejectsAuthorizationWhenPublicHelpersAreDisabled() {
        when(oidcConfig.publicEndpointsEnabled()).thenReturn(false);

        final var problem = assertThrows(
                HttpProblem.class,
                () -> resource.authorize(
                        "https://app.example.com/callback",
                        "challenge",
                        "S256",
                        null,
                        "https://idp.example.com/authorize"));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), problem.getStatusCode());
    }

    @Test
    void storesPkceChallengeAndRedirectsToProvider() {
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isRequired()).thenReturn(true);
        when(pkceService.isValidChallengeMethod("S256")).thenReturn(true);
        when(pkceService.generateState()).thenReturn("generated-state");
        when(pkceService.storeChallenge("generated-state", "challenge"))
                .thenReturn(Uni.createFrom().voidItem());

        final var response = resource.authorize(
                        "https://app.example.com/callback",
                        "challenge",
                        "S256",
                        "client-state",
                        "https://idp.example.com/authorize")
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(response.getLocation().toString().contains("state=generated-state"));
        verify(pkceService).storeChallenge("generated-state", "challenge");
    }

    private void setupExchange() {
        when(pkceConfig.enabled()).thenReturn(true);
        when(pkceService.isRequired()).thenReturn(false);
        when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        when(tokenExchangeConfig.enabled()).thenReturn(true);
        when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
        when(tokenExchangeConfig.clientId()).thenReturn(Optional.of("client"));
        when(tokenExchangeConfig.clientSecret()).thenReturn(Optional.empty());
        when(tokenExchangeConfig.clientAuthMethod()).thenReturn("none");
        when(tokenExchangeConfig.scopes()).thenReturn(Set.of("openid"));
        when(tokenExchangeConfig.createSession()).thenReturn(true);
        when(sessionConfig.enabled()).thenReturn(true);
        when(tokenExchangeRegistry.getProvider()).thenReturn(tokenExchangeProvider);
    }

    private OidcTokenExchangeResponse tokenResponse() {
        return new OidcTokenExchangeResponse(
                "access-token", Optional.of("id-token"), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
    }

    private ValidatedIdentity identity() {
        final var expiresAt = Instant.now().plusSeconds(3600);
        return new ValidatedIdentity(
                "configured-idp",
                "user-1",
                "https://idp.example.com",
                Set.of("client"),
                Optional.empty(),
                Optional.of("token-1"),
                Map.of("sub", "user-1", "exp", expiresAt.getEpochSecond()),
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
}
