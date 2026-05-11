package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

@DisplayName("ApiKeyAuthenticationMechanism")
class ApiKeyAuthenticationMechanismTest {

    private static final String NOOP_PROPERTY = "aussie.auth.dangerous-noop";

    private ApiKeyAuthenticationMechanism mechanism;
    private IdentityProviderManager identityProviderManager;
    private RoutingContext routingContext;
    private HttpServerRequest httpRequest;
    private String previousNoopProperty;

    @BeforeEach
    void setUp() {
        // The test classpath pins aussie.auth.dangerous-noop=true, which would short-circuit
        // every no-token / deferred-token path into the noop identity. Pin it false here so
        // the JWT-shape guard's actual return value (nullItem) is observable.
        previousNoopProperty = System.setProperty(NOOP_PROPERTY, "false");

        identityProviderManager = mock(IdentityProviderManager.class);
        routingContext = mock(RoutingContext.class);
        httpRequest = mock(HttpServerRequest.class);

        when(routingContext.request()).thenReturn(httpRequest);
        when(httpRequest.path()).thenReturn("/admin/services");

        mechanism = new ApiKeyAuthenticationMechanism();
    }

    @AfterEach
    void tearDown() {
        if (previousNoopProperty == null) {
            System.clearProperty(NOOP_PROPERTY);
        } else {
            System.setProperty(NOOP_PROPERTY, previousNoopProperty);
        }
    }

    @Nested
    @DisplayName("authenticate")
    class AuthenticateTests {

        @Test
        @DisplayName("delegates plain API-key-shaped tokens to the identity provider")
        void delegatesApiKeyShapedTokens() {
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer pXMQ9bL5e3kxF1abc-def_GHI");

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            assertEquals(mockIdentity, result);

            ArgumentCaptor<ApiKeyAuthenticationRequest> captor =
                    ArgumentCaptor.forClass(ApiKeyAuthenticationRequest.class);
            verify(identityProviderManager).authenticate(captor.capture());
            assertEquals("pXMQ9bL5e3kxF1abc-def_GHI", captor.getValue().getApiKey());
        }

        @Test
        @DisplayName("delegates aussie_-prefixed dotted tokens to the API-key provider (not deferred)")
        void delegatesAussiePrefixedDottedTokens() {
            // JwtAuthenticationMechanism defers anything starting with "aussie_" back to this
            // mechanism. Without the API_KEY_PREFIX bypass, a three-dot-segment key like
            // "aussie_foo.bar.baz" would be deferred by both and the request would 401 with no
            // provider running.
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer aussie_foo.bar.baz");

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            verify(identityProviderManager).authenticate(any(ApiKeyAuthenticationRequest.class));
        }

        @Test
        @DisplayName("defers JWT-shaped tokens so JwtAuthenticationMechanism can handle them")
        void defersJwtShapedTokens() {
            // Three non-empty dot-separated parts — the JWS compact serialization shape.
            // Without this guard the JWT would be treated as an API-key lookup miss and
            // would 401 before JwtAuthenticationMechanism ever runs.
            when(httpRequest.getHeader("Authorization"))
                    .thenReturn("Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0In0.sig");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("treats two-part dotted tokens as API keys (not JWTs)")
        void delegatesTwoPartDottedTokens() {
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer header.payload");

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            verify(identityProviderManager).authenticate(any(ApiKeyAuthenticationRequest.class));
        }

        @Test
        @DisplayName("treats four-part dotted tokens as API keys (not JWTs)")
        void delegatesFourPartDottedTokens() {
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer a.b.c.d");

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            verify(identityProviderManager).authenticate(any(ApiKeyAuthenticationRequest.class));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {".b.c", "a..c", "a.b."})
        @DisplayName("treats three-part tokens with empty segments as API keys (not JWTs)")
        void delegatesThreePartTokensWithEmptySegments(String token) {
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer " + token);

            var mockIdentity = mock(SecurityIdentity.class);
            when(identityProviderManager.authenticate(any(ApiKeyAuthenticationRequest.class)))
                    .thenReturn(Uni.createFrom().item(mockIdentity));

            mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            verify(identityProviderManager).authenticate(any(ApiKeyAuthenticationRequest.class));
        }

        @Test
        @DisplayName("returns null when no Authorization header is present")
        void returnsNullWhenNoHeader() {
            when(httpRequest.getHeader("Authorization")).thenReturn(null);

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }

        @Test
        @DisplayName("returns null when Bearer token is blank")
        void returnsNullWhenBlankBearer() {
            when(httpRequest.getHeader("Authorization")).thenReturn("Bearer    ");

            SecurityIdentity result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNull(result);
            verify(identityProviderManager, never()).authenticate(any());
        }
    }
}
