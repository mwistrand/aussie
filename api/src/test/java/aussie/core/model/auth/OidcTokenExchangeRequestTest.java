package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;

/**
 * Unit tests for OidcTokenExchangeRequest.
 */
@DisplayName("OidcTokenExchangeRequest")
class OidcTokenExchangeRequestTest {

    private static final String CODE = "test-auth-code";
    private static final String REDIRECT_URI = "https://example.com/callback";
    private static final String TOKEN_ENDPOINT = "https://idp.example.com/token";
    private static final String CLIENT_ID = "test-client";
    private static final String CLIENT_SECRET = "test-secret";

    @Nested
    @DisplayName("Construction validation")
    class ConstructionValidation {

        @Test
        @DisplayName("should create request with all required fields")
        void shouldCreateRequestWithRequiredFields() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            assertEquals(CODE, request.authorizationCode());
            assertEquals(REDIRECT_URI, request.redirectUri());
            assertEquals(TOKEN_ENDPOINT, request.tokenEndpoint());
            assertEquals(CLIENT_ID, request.clientId());
            assertEquals(CLIENT_SECRET, request.clientSecret());
            assertEquals(ClientAuthMethod.CLIENT_SECRET_BASIC, request.clientAuthMethod());
        }

        @Test
        @DisplayName("should require authorization code")
        void shouldRequireAuthorizationCode() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeRequest(
                            null,
                            REDIRECT_URI,
                            Optional.empty(),
                            TOKEN_ENDPOINT,
                            CLIENT_ID,
                            CLIENT_SECRET,
                            ClientAuthMethod.CLIENT_SECRET_BASIC,
                            Optional.empty()));
        }

        @Test
        @DisplayName("should reject blank authorization code")
        void shouldRejectBlankAuthorizationCode() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeRequest(
                            "   ",
                            REDIRECT_URI,
                            Optional.empty(),
                            TOKEN_ENDPOINT,
                            CLIENT_ID,
                            CLIENT_SECRET,
                            ClientAuthMethod.CLIENT_SECRET_BASIC,
                            Optional.empty()));
        }

        @Test
        @DisplayName("should require token endpoint")
        void shouldRequireTokenEndpoint() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeRequest(
                            CODE,
                            REDIRECT_URI,
                            Optional.empty(),
                            null,
                            CLIENT_ID,
                            CLIENT_SECRET,
                            ClientAuthMethod.CLIENT_SECRET_BASIC,
                            Optional.empty()));
        }

        @Test
        @DisplayName("should require client ID")
        void shouldRequireClientId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeRequest(
                            CODE,
                            REDIRECT_URI,
                            Optional.empty(),
                            TOKEN_ENDPOINT,
                            null,
                            CLIENT_SECRET,
                            ClientAuthMethod.CLIENT_SECRET_BASIC,
                            Optional.empty()));
        }

        @Test
        @DisplayName("should default null client auth method to CLIENT_SECRET_BASIC")
        void shouldDefaultClientAuthMethod() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    null,
                    Optional.empty());

            assertEquals(ClientAuthMethod.CLIENT_SECRET_BASIC, request.clientAuthMethod());
        }

        @Test
        @DisplayName("should default null code verifier to empty Optional")
        void shouldDefaultCodeVerifier() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    null,
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            assertNotNull(request.codeVerifier());
            assertTrue(request.codeVerifier().isEmpty());
        }

        @Test
        @DisplayName("should default null scopes to empty Optional")
        void shouldDefaultScopes() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    null);

            assertNotNull(request.scopes());
            assertTrue(request.scopes().isEmpty());
        }

        @Test
        @DisplayName("should allow null redirect URI")
        void shouldAllowNullRedirectUri() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    null,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            // Should not throw
            assertNotNull(request);
        }

        @Test
        @DisplayName("should allow null client secret for public clients")
        void shouldAllowNullClientSecret() {
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    null,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            // Should not throw - public clients may not have a secret
            assertNotNull(request);
        }
    }

    @Nested
    @DisplayName("Optional fields")
    class OptionalFields {

        @Test
        @DisplayName("should preserve code verifier when provided")
        void shouldPreserveCodeVerifier() {
            var verifier = "test-code-verifier";
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.of(verifier),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.empty());

            assertTrue(request.codeVerifier().isPresent());
            assertEquals(verifier, request.codeVerifier().get());
        }

        @Test
        @DisplayName("should preserve scopes when provided")
        void shouldPreserveScopes() {
            var scopes = "openid profile email";
            var request = new OidcTokenExchangeRequest(
                    CODE,
                    REDIRECT_URI,
                    Optional.empty(),
                    TOKEN_ENDPOINT,
                    CLIENT_ID,
                    CLIENT_SECRET,
                    ClientAuthMethod.CLIENT_SECRET_BASIC,
                    Optional.of(scopes));

            assertTrue(request.scopes().isPresent());
            assertEquals(scopes, request.scopes().get());
        }
    }
}
