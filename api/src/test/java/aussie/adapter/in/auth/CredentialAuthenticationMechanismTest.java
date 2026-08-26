package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.common.context.ClientContext;
import aussie.common.context.RouteContextAttributes;
import aussie.core.config.ApiKeyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.ApiKeyService;
import aussie.core.service.auth.TokenValidationService;

@DisplayName("Credential authentication dispatcher")
class CredentialAuthenticationMechanismTest {

    private static final String VERSIONED_KEY = ApiKeyService.API_KEY_PREFIX + "A".repeat(43);
    private static final String LEGACY_KEY = "A".repeat(32);
    private static final String JWT = "header.payload.signature";
    private static final String NOOP_PROPERTY = "aussie.auth.dangerous-noop";

    private TokenValidationService tokenValidationService;
    private SessionConfig sessionConfig;
    private SessionCookieManager cookieManager;
    private SessionManagement sessionManagement;
    private GatewayMetrics metrics;
    private SecurityMonitor securityMonitor;
    private ClientContextResolver clientContextResolver;
    private IdentityProviderManager identityProviderManager;
    private RoutingContext routingContext;
    private HttpServerRequest request;
    private MultiMap headers;
    private String previousNoopProperty;

    @BeforeEach
    void setUp() {
        previousNoopProperty = System.setProperty(NOOP_PROPERTY, "false");
        tokenValidationService = mock(TokenValidationService.class);
        sessionConfig = mock(SessionConfig.class);
        cookieManager = mock(SessionCookieManager.class);
        sessionManagement = mock(SessionManagement.class);
        metrics = mock(GatewayMetrics.class);
        securityMonitor = mock(SecurityMonitor.class);
        clientContextResolver = mock(ClientContextResolver.class);
        identityProviderManager = mock(IdentityProviderManager.class);
        routingContext = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        headers = mock(MultiMap.class);

        when(routingContext.request()).thenReturn(request);
        when(request.headers()).thenReturn(headers);
        when(headers.getAll("Authorization")).thenReturn(List.of());
        when(sessionConfig.enabled()).thenReturn(true);
        when(cookieManager.hasSessionCookie(request)).thenReturn(false);
        when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext("192.0.2.1", false, null));
    }

    @AfterEach
    void tearDown() {
        if (previousNoopProperty == null) {
            System.clearProperty(NOOP_PROPERTY);
        } else {
            System.setProperty(NOOP_PROPERTY, previousNoopProperty);
        }
    }

    private CredentialAuthenticationMechanism mechanism(boolean acceptLegacyApiKeys) {
        final var apiKeyConfig = mock(ApiKeyConfig.class);
        when(apiKeyConfig.acceptLegacyFormat()).thenReturn(acceptLegacyApiKeys);
        return new CredentialAuthenticationMechanism(
                tokenValidationService,
                sessionConfig,
                cookieManager,
                sessionManagement,
                metrics,
                securityMonitor,
                clientContextResolver,
                apiKeyConfig);
    }

    @Test
    void publicRoutesSkipCredentialParsing() {
        when(routingContext.get(RouteContextAttributes.PUBLIC)).thenReturn(Boolean.TRUE);

        assertNull(mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));
        verify(routingContext, never()).request();
    }

    @Test
    void dispatchesVersionedApiKey() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer " + VERSIONED_KEY));
        final var expected = mock(SecurityIdentity.class);
        when(expected.getAttribute("principalId")).thenReturn("key-1");
        when(expected.getAttribute("credentialId")).thenReturn("key-1");
        when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                .thenReturn(Uni.createFrom().item(expected));

        final var actual = mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1));

        assertEquals(expected, actual);
        final var requestCaptor = ArgumentCaptor.forClass(ApiKeyAuthenticationRequest.class);
        verify(identityProviderManager).authenticate(requestCaptor.capture());
        assertEquals(VERSIONED_KEY, requestCaptor.getValue().getApiKey());
        verify(clientContextResolver).attachVerifiedIdentity(routingContext, "key-1", "key-1");
    }

    @Test
    void dispatchesJwtWithoutTryingApiKeyValidation() {
        when(headers.getAll("Authorization")).thenReturn(List.of("bearer " + JWT));
        when(tokenValidationService.isEnabled()).thenReturn(true);
        when(identityProviderManager.authenticate(any(JwtAuthenticationRequest.class)))
                .thenReturn(Uni.createFrom().item(mock(SecurityIdentity.class)));

        mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1));

        verify(identityProviderManager).authenticate(any(JwtAuthenticationRequest.class));
        verify(identityProviderManager, never()).authenticate(any(ApiKeyAuthenticationRequest.class));
    }

    @Test
    void rejectsAmbiguousUnprefixedCredentialByDefault() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer legacy-key"));

        assertThrows(AuthenticationFailedException.class, () -> mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        verify(identityProviderManager, never()).authenticate(any());
        verify(securityMonitor).recordAuthFailure("192.0.2.1", "ambiguous_credential", "bearer");
    }

    @Test
    void explicitlyEnabledCompatibilityWindowAcceptsLegacyKey() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer " + LEGACY_KEY));
        when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                .thenReturn(Uni.createFrom().item(mock(SecurityIdentity.class)));

        mechanism(true)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1));

        verify(identityProviderManager).authenticate(any(ApiKeyAuthenticationRequest.class));
    }

    @Test
    void compatibilityWindowRejectsCredentialsBelowTheLegacyMinimum() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer opaque-token"));

        assertThrows(AuthenticationFailedException.class, () -> mechanism(true)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        verify(identityProviderManager, never()).authenticate(any());
    }

    @Test
    void rejectsConflictingCredentialsBeforeEitherIsValidated() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer " + VERSIONED_KEY));
        when(cookieManager.hasSessionCookie(request)).thenReturn(true);

        assertThrows(AuthenticationFailedException.class, () -> mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        verify(identityProviderManager, never()).authenticate(any());
        verify(sessionManagement, never()).getSession(any());
        verify(securityMonitor).recordAuthFailure("192.0.2.1", "conflicting_authentication", "multiple");
    }

    @Test
    void rejectsDuplicateAuthorizationHeaders() {
        when(headers.getAll("Authorization")).thenReturn(List.of("Bearer " + VERSIONED_KEY, "Bearer " + VERSIONED_KEY));

        assertThrows(AuthenticationFailedException.class, () -> mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        verify(identityProviderManager, never()).authenticate(any());
    }

    @Test
    void authenticatesSessionIntoTheSharedIdentityShape() {
        when(cookieManager.hasSessionCookie(request)).thenReturn(true);
        when(cookieManager.extractSessionId(request)).thenReturn(Optional.of("session-1"));
        when(sessionConfig.slidingExpiration()).thenReturn(false);
        when(sessionManagement.getSession("session-1"))
                .thenReturn(Uni.createFrom().item(Optional.of(session())));

        final var identity = mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1));

        assertNotNull(identity);
        assertInstanceOf(CredentialAuthenticationMechanism.SessionPrincipal.class, identity.getPrincipal());
        assertEquals(Set.of("service.config.read"), identity.getAttribute("permissions"));
        assertEquals("session", identity.getAttribute("authenticationMethod"));
        assertTrue(identity.getRoles().contains("service.config.read"));
        verify(clientContextResolver).attachVerifiedIdentity(routingContext, "user-1", "session-1");
    }

    @Test
    void invalidSessionFailsInsteadOfFallingThrough() {
        when(cookieManager.hasSessionCookie(request)).thenReturn(true);
        when(cookieManager.extractSessionId(request)).thenReturn(Optional.of("missing"));
        when(sessionManagement.getSession("missing"))
                .thenReturn(Uni.createFrom().item(Optional.empty()));

        assertThrows(AuthenticationFailedException.class, () -> mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        verify(metrics).recordAuthFailure("invalid_session", "192.0.2.1");
    }

    @Test
    void noopModeBypassesCredentialParsing() {
        System.setProperty(NOOP_PROPERTY, "true");
        when(headers.getAll("Authorization")).thenReturn(List.of("invalid", "duplicate"));

        final var identity = mechanism(false)
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1));

        assertEquals("development-mode", identity.getPrincipal().getName());
        assertEquals("dangerous-noop", identity.getAttribute("authenticationMethod"));
        verify(routingContext, never()).request();
    }

    private Session session() {
        return new Session(
                "session-1",
                "user-1",
                "https://issuer.example",
                Map.of("sub", "user-1"),
                Set.of("service.config.read"),
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Instant.now(),
                null,
                null);
    }
}
