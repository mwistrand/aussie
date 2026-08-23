package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.net.SocketAddress;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.OidcConfig;
import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.routing.UpstreamAddressResolver;

@DisplayName("DefaultOidcTokenExchangeProvider")
@ExtendWith(MockitoExtension.class)
class DefaultOidcTokenExchangeProviderTest {

    @Mock
    private OidcConfig oidcConfig;

    @Mock
    private OidcConfig.TokenExchangeConfig tokenExchangeConfig;

    @Mock
    private WebClient webClient;

    @Mock
    private UpstreamAddressResolver addressResolver;

    @Mock
    private OutboundHttpClients outboundClient;

    private DefaultOidcTokenExchangeProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(oidcConfig.tokenExchange()).thenReturn(tokenExchangeConfig);
        lenient().when(tokenExchangeConfig.timeout()).thenReturn(Duration.ofSeconds(10));

        lenient().when(outboundClient.webClient()).thenReturn(webClient);
        lenient()
                .when(addressResolver.resolve(any(URI.class)))
                .thenReturn(Uni.createFrom().item(io.vertx.core.net.SocketAddress.inetSocketAddress(443, "192.0.2.1")));
        provider = new DefaultOidcTokenExchangeProvider(outboundClient, oidcConfig, addressResolver);
    }

    @Nested
    @DisplayName("name()")
    class Name {

        @Test
        @DisplayName("shouldReturnDefault")
        void shouldReturnDefault() {
            assertEquals("default", provider.name());
        }
    }

    @Nested
    @DisplayName("priority()")
    class Priority {

        @Test
        @DisplayName("shouldReturn100")
        void shouldReturn100() {
            assertEquals(100, provider.priority());
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailable {

        @Test
        @DisplayName("shouldReturnTrueWhenTokenEndpointPresentAndNonBlank")
        void shouldReturnTrueWhenTokenEndpointPresentAndNonBlank() {
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));
            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("shouldReturnFalseWhenTokenEndpointPresentButBlank")
        void shouldReturnFalseWhenTokenEndpointPresentButBlank() {
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("  "));
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("shouldReturnFalseWhenTokenEndpointEmpty")
        void shouldReturnFalseWhenTokenEndpointEmpty() {
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.empty());
            assertFalse(provider.isAvailable());
        }
    }

    @Nested
    @DisplayName("healthCheck()")
    class HealthCheck {

        @Test
        @DisplayName("shouldReturnUpWhenTokenEndpointPresent")
        void shouldReturnUpWhenTokenEndpointPresent() {
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.of("https://idp.example.com/token"));

            var result = provider.healthCheck();

            assertTrue(result.isPresent());
            assertEquals("oidc-token-exchange-default", result.get().getName());
            assertEquals(
                    org.eclipse.microprofile.health.HealthCheckResponse.Status.UP,
                    result.get().getStatus());
        }

        @Test
        @DisplayName("shouldReturnDownWhenTokenEndpointNotConfigured")
        void shouldReturnDownWhenTokenEndpointNotConfigured() {
            when(tokenExchangeConfig.tokenEndpoint()).thenReturn(Optional.empty());

            var result = provider.healthCheck();

            assertTrue(result.isPresent());
            assertEquals(
                    org.eclipse.microprofile.health.HealthCheckResponse.Status.DOWN,
                    result.get().getStatus());
        }
    }

    @Nested
    @DisplayName("exchange()")
    class Exchange {

        @SuppressWarnings("unchecked")
        private HttpRequest<Buffer> setupMockRequest() {
            HttpRequest<Buffer> httpRequest = mock(HttpRequest.class);
            when(webClient.requestAbs(any(), any(SocketAddress.class), anyString()))
                    .thenReturn(httpRequest);
            when(httpRequest.ssl(anyBoolean())).thenReturn(httpRequest);
            when(httpRequest.followRedirects(anyBoolean())).thenReturn(httpRequest);
            lenient().when(httpRequest.timeout(anyLong())).thenReturn(httpRequest);
            lenient().when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
            return httpRequest;
        }

        @Test
        @DisplayName("shouldUseBasicAuthWhenClientSecretBasic")
        @SuppressWarnings("unchecked")
        void shouldUseBasicAuthWhenClientSecretBasic() {
            var httpRequest = setupMockRequest();

            var json = new JsonObject()
                    .put("access_token", "at-123")
                    .put("token_type", "Bearer")
                    .put("expires_in", 3600);
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.bodyAsJsonObject()).thenReturn(json);
            when(httpRequest.sendBuffer(any(Buffer.class)))
                    .thenReturn(Uni.createFrom().item(response));

            var request = new OidcTokenExchangeRequest(
                    "auth-code-123",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "client-secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var result = provider.exchange(request).await().atMost(Duration.ofSeconds(5));

            assertEquals("at-123", result.accessToken());
            verify(httpRequest)
                    .putHeader(
                            "Authorization",
                            "Basic "
                                    + java.util.Base64.getEncoder()
                                            .encodeToString("client-id:client-secret"
                                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Test
        @DisplayName("shouldIncludeCredentialsInBodyWhenClientSecretPost")
        @SuppressWarnings("unchecked")
        void shouldIncludeCredentialsInBodyWhenClientSecretPost() {
            var httpRequest = setupMockRequest();

            var json = new JsonObject()
                    .put("access_token", "at-456")
                    .put("token_type", "Bearer")
                    .put("expires_in", 3600);
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.bodyAsJsonObject()).thenReturn(json);

            var bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
            when(httpRequest.sendBuffer(bodyCaptor.capture()))
                    .thenReturn(Uni.createFrom().item(response));

            var request = new OidcTokenExchangeRequest(
                    "auth-code-456",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "client-secret",
                    ClientAuthMethod.CLIENT_SECRET_POST,
                    Optional.empty());

            var result = provider.exchange(request).await().atMost(Duration.ofSeconds(5));

            assertEquals("at-456", result.accessToken());
            var formBody = bodyCaptor.getValue().toString();
            assertTrue(formBody.contains("client_id=client-id"));
            assertTrue(formBody.contains("client_secret=client-secret"));
        }

        @Test
        @DisplayName("shouldIncludeCodeVerifierWhenPresent")
        @SuppressWarnings("unchecked")
        void shouldIncludeCodeVerifierWhenPresent() {
            var httpRequest = setupMockRequest();

            var json = new JsonObject()
                    .put("access_token", "at-789")
                    .put("token_type", "Bearer")
                    .put("expires_in", 3600);
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.bodyAsJsonObject()).thenReturn(json);

            var bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
            when(httpRequest.sendBuffer(bodyCaptor.capture()))
                    .thenReturn(Uni.createFrom().item(response));

            var request = new OidcTokenExchangeRequest(
                    "auth-code-789",
                    "https://app.example.com/callback",
                    Optional.of("pkce-verifier-abc"),
                    "https://idp.example.com/token",
                    "client-id",
                    "client-secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            provider.exchange(request).await().atMost(Duration.ofSeconds(5));

            var formBody = bodyCaptor.getValue().toString();
            assertTrue(formBody.contains("code_verifier=pkce-verifier-abc"));
        }

        @Test
        @DisplayName("shouldIncludeScopesWhenPresent")
        @SuppressWarnings("unchecked")
        void shouldIncludeScopesWhenPresent() {
            var httpRequest = setupMockRequest();

            var json = new JsonObject()
                    .put("access_token", "at-scope")
                    .put("token_type", "Bearer")
                    .put("expires_in", 3600);
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.bodyAsJsonObject()).thenReturn(json);

            var bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
            when(httpRequest.sendBuffer(bodyCaptor.capture()))
                    .thenReturn(Uni.createFrom().item(response));

            var request = new OidcTokenExchangeRequest(
                    "auth-code-scope",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "client-secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.of("openid profile"));

            provider.exchange(request).await().atMost(Duration.ofSeconds(5));

            var formBody = bodyCaptor.getValue().toString();
            assertTrue(formBody.contains("scope=openid+profile"));
        }

        @Test
        @DisplayName("shouldIncludeAddressResolutionInRequestTimeout")
        void shouldIncludeAddressResolutionInRequestTimeout() {
            when(tokenExchangeConfig.timeout()).thenReturn(Duration.ofMillis(10));
            when(addressResolver.resolve(any(URI.class)))
                    .thenReturn(Uni.createFrom().nothing());
            var request = new OidcTokenExchangeRequest(
                    "auth-code",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "client-secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            assertThrows(
                    HttpProblem.class, () -> provider.exchange(request).await().atMost(Duration.ofSeconds(1)));
            verify(webClient, never()).requestAbs(any(), any(SocketAddress.class), anyString());
        }
    }

    @Nested
    @DisplayName("buildFormBody()")
    class BuildFormBody {

        private String invokeBuildFormBody(OidcTokenExchangeRequest request) throws Exception {
            var method = DefaultOidcTokenExchangeProvider.class.getDeclaredMethod(
                    "buildFormBody", OidcTokenExchangeRequest.class);
            method.setAccessible(true);
            return (String) method.invoke(provider, request);
        }

        @Test
        @DisplayName("shouldExcludeRedirectUriWhenNull")
        void shouldExcludeRedirectUriWhenNull() throws Exception {
            var request = new OidcTokenExchangeRequest(
                    "code-123",
                    null,
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var body = invokeBuildFormBody(request);

            assertFalse(body.contains("redirect_uri"));
        }

        @Test
        @DisplayName("shouldExcludeRedirectUriWhenBlank")
        void shouldExcludeRedirectUriWhenBlank() throws Exception {
            var request = new OidcTokenExchangeRequest(
                    "code-123",
                    "  ",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var body = invokeBuildFormBody(request);

            assertFalse(body.contains("redirect_uri"));
        }

        @Test
        @DisplayName("shouldIncludeRedirectUriWhenPresent")
        void shouldIncludeRedirectUriWhenPresent() throws Exception {
            var request = new OidcTokenExchangeRequest(
                    "code-123",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var body = invokeBuildFormBody(request);

            assertTrue(body.contains("redirect_uri="));
        }

        @Test
        @DisplayName("shouldExcludeCodeVerifierWhenAbsent")
        void shouldExcludeCodeVerifierWhenAbsent() throws Exception {
            var request = new OidcTokenExchangeRequest(
                    "code-123",
                    "https://app.example.com/callback",
                    Optional.empty(),
                    "https://idp.example.com/token",
                    "client-id",
                    "secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var body = invokeBuildFormBody(request);

            assertFalse(body.contains("code_verifier"));
        }

        @Test
        @DisplayName("shouldIncludeCodeVerifierWhenPresent")
        void shouldIncludeCodeVerifierWhenPresent() throws Exception {
            var request = new OidcTokenExchangeRequest(
                    "code-123",
                    "https://app.example.com/callback",
                    Optional.of("verifier-abc"),
                    "https://idp.example.com/token",
                    "client-id",
                    "secret",
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            var body = invokeBuildFormBody(request);

            assertTrue(body.contains("code_verifier=verifier-abc"));
        }
    }

    @Nested
    @DisplayName("parseTokenResponse()")
    class ParseTokenResponse {

        @SuppressWarnings("unchecked")
        private Uni<aussie.core.model.auth.OidcTokenExchangeResponse> invokeParseTokenResponse(
                HttpResponse<Buffer> response) throws Exception {
            var method =
                    DefaultOidcTokenExchangeProvider.class.getDeclaredMethod("parseTokenResponse", HttpResponse.class);
            method.setAccessible(true);
            return (Uni<aussie.core.model.auth.OidcTokenExchangeResponse>) method.invoke(provider, response);
        }

        @Test
        @DisplayName("shouldReturnFailureForNon200Status")
        @SuppressWarnings("unchecked")
        void shouldReturnFailureForNon200Status() throws Exception {
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(400);
            when(response.bodyAsString()).thenReturn("{\"error\":\"invalid_grant\"}");

            try {
                invokeParseTokenResponse(response).await().atMost(Duration.ofSeconds(5));
                fail("Should have thrown");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("400")
                        || e.getCause().getMessage().contains("400"));
            }
        }

        @Test
        @DisplayName("shouldParseSuccessResponseWithAllFields")
        @SuppressWarnings("unchecked")
        void shouldParseSuccessResponseWithAllFields() throws Exception {
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);

            var json = new JsonObject()
                    .put("access_token", "at-full")
                    .put("id_token", "id-token-123")
                    .put("refresh_token", "rt-456")
                    .put("token_type", "Bearer")
                    .put("expires_in", 7200)
                    .put("scope", "openid profile")
                    .put("custom_claim", "custom_value");
            when(response.bodyAsJsonObject()).thenReturn(json);

            var result = invokeParseTokenResponse(response).await().atMost(Duration.ofSeconds(5));

            assertEquals("at-full", result.accessToken());
            assertTrue(result.idToken().isPresent());
            assertEquals("id-token-123", result.idToken().get());
            assertTrue(result.refreshToken().isPresent());
            assertEquals("rt-456", result.refreshToken().get());
            assertEquals("Bearer", result.tokenType());
            assertEquals(7200, result.expiresIn());
            assertTrue(result.scope().isPresent());
            assertEquals("openid profile", result.scope().get());
            assertEquals("custom_value", result.additionalClaims().get("custom_claim"));
        }

        @Test
        @DisplayName("shouldReturnFailureWhenAccessTokenMissing")
        @SuppressWarnings("unchecked")
        void shouldReturnFailureWhenAccessTokenMissing() throws Exception {
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);

            var json = new JsonObject().put("token_type", "Bearer").put("expires_in", 3600);
            when(response.bodyAsJsonObject()).thenReturn(json);

            try {
                invokeParseTokenResponse(response).await().atMost(Duration.ofSeconds(5));
                fail("Should have thrown");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("access_token")
                        || e.getCause().getMessage().contains("access_token"));
            }
        }

        @Test
        @DisplayName("shouldUseDefaultsForMissingOptionalFields")
        @SuppressWarnings("unchecked")
        void shouldUseDefaultsForMissingOptionalFields() throws Exception {
            HttpResponse<Buffer> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);

            var json = new JsonObject().put("access_token", "at-minimal");
            when(response.bodyAsJsonObject()).thenReturn(json);

            var result = invokeParseTokenResponse(response).await().atMost(Duration.ofSeconds(5));

            assertEquals("at-minimal", result.accessToken());
            assertEquals("Bearer", result.tokenType());
            assertEquals(3600, result.expiresIn());
            assertFalse(result.idToken().isPresent());
            assertFalse(result.refreshToken().isPresent());
            assertFalse(result.scope().isPresent());
        }
    }

    @Nested
    @DisplayName("isAdditionalClaim()")
    class IsAdditionalClaim {

        private boolean invokeIsAdditionalClaim(String key) throws Exception {
            var method = DefaultOidcTokenExchangeProvider.class.getDeclaredMethod("isAdditionalClaim", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(provider, key);
        }

        @Test
        @DisplayName("shouldReturnFalseForAccessToken")
        void shouldReturnFalseForAccessToken() throws Exception {
            assertFalse(invokeIsAdditionalClaim("access_token"));
        }

        @Test
        @DisplayName("shouldReturnFalseForIdToken")
        void shouldReturnFalseForIdToken() throws Exception {
            assertFalse(invokeIsAdditionalClaim("id_token"));
        }

        @Test
        @DisplayName("shouldReturnFalseForRefreshToken")
        void shouldReturnFalseForRefreshToken() throws Exception {
            assertFalse(invokeIsAdditionalClaim("refresh_token"));
        }

        @Test
        @DisplayName("shouldReturnFalseForTokenType")
        void shouldReturnFalseForTokenType() throws Exception {
            assertFalse(invokeIsAdditionalClaim("token_type"));
        }

        @Test
        @DisplayName("shouldReturnFalseForExpiresIn")
        void shouldReturnFalseForExpiresIn() throws Exception {
            assertFalse(invokeIsAdditionalClaim("expires_in"));
        }

        @Test
        @DisplayName("shouldReturnFalseForScope")
        void shouldReturnFalseForScope() throws Exception {
            assertFalse(invokeIsAdditionalClaim("scope"));
        }

        @Test
        @DisplayName("shouldReturnTrueForUnknownField")
        void shouldReturnTrueForUnknownField() throws Exception {
            assertTrue(invokeIsAdditionalClaim("custom_field"));
        }

        @Test
        @DisplayName("shouldReturnTrueForAnotherUnknownField")
        void shouldReturnTrueForAnotherUnknownField() throws Exception {
            assertTrue(invokeIsAdditionalClaim("session_state"));
        }
    }
}
