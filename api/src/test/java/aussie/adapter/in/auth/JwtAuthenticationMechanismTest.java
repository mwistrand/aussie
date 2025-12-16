package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.core.service.auth.TokenValidationService;

@DisplayName("JwtAuthenticationMechanism")
class JwtAuthenticationMechanismTest {

    private JwtAuthenticationMechanism mechanism;
    private TokenValidationService tokenValidationService;
    private IdentityProviderManager identityProviderManager;
    private RoutingContext routingContext;
    private HttpServerRequest httpRequest;

    @BeforeEach
    void setUp() {
        tokenValidationService = mock(TokenValidationService.class);
        identityProviderManager = mock(IdentityProviderManager.class);
        routingContext = mock(RoutingContext.class);
        httpRequest = mock(HttpServerRequest.class);

        when(routingContext.request()).thenReturn(httpRequest);

        mechanism = new JwtAuthenticationMechanism(tokenValidationService);
    }

    @Nested
    @DisplayName("authenticate")
    class AuthenticateTests {

        @Test
        @DisplayName("should return null when token validation is not enabled")
        void shouldReturnNullWhenNotEnabled() {
            when(tokenValidationService.isEnabled()).thenReturn(false);
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer some-token");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should return null when no Authorization header")
        void shouldReturnNullWhenNoAuthHeader() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization")).thenReturn(null);

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should return null when Authorization header is blank")
        void shouldReturnNullWhenAuthHeaderBlank() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization")).thenReturn("   ");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should return null when Authorization header does not start with Bearer")
        void shouldReturnNullWhenNotBearer() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should return null when Bearer token is blank")
        void shouldReturnNullWhenBearerTokenBlank() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer    ");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should return null when token looks like an API key")
        void shouldReturnNullWhenTokenIsApiKey() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer aussie_abc123xyz");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("should delegate to identity provider for JWT-like tokens")
        void shouldDelegateToIdentityProviderForJwt() {
            when(tokenValidationService.isEnabled()).thenReturn(true);
            when(httpRequest.getHeader("Authorization"))
                    .thenReturn("Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.sig");
            when(httpRequest.path()).thenReturn("/api/test");

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(JwtAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .indefinitely();

            assertNotNull(result);
            assertEquals(mockIdentity, result);

            // Verify the request was created with the correct token
            ArgumentCaptor<JwtAuthenticationRequest> captor = ArgumentCaptor.forClass(JwtAuthenticationRequest.class);
            verify(identityProviderManager).authenticate(captor.capture());
            assertEquals(
                    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.sig",
                    captor.getValue().getToken());
        }
    }

    @Nested
    @DisplayName("getChallenge")
    class GetChallengeTests {

        @Test
        @DisplayName("should return 401 with WWW-Authenticate header")
        void shouldReturn401Challenge() {
            var challenge = mechanism.getChallenge(routingContext).await().indefinitely();

            assertNotNull(challenge);
            assertEquals(401, challenge.status);
            assertEquals("WWW-Authenticate", challenge.headerName.toString());
            assertEquals("Bearer realm=\"aussie\"", challenge.headerContent.toString());
        }
    }

    @Nested
    @DisplayName("getCredentialTypes")
    class GetCredentialTypesTests {

        @Test
        @DisplayName("should return JwtAuthenticationRequest class")
        void shouldReturnCorrectCredentialType() {
            Set<Class<? extends AuthenticationRequest>> types = mechanism.getCredentialTypes();

            assertEquals(1, types.size());
            assertTrue(types.contains(JwtAuthenticationRequest.class));
        }
    }

    @Nested
    @DisplayName("getCredentialTransport")
    class GetCredentialTransportTests {

        @Test
        @DisplayName("should return non-null credential transport")
        void shouldReturnCredentialTransport() {
            var transport =
                    mechanism.getCredentialTransport(routingContext).await().indefinitely();

            assertNotNull(transport);
        }
    }
}
