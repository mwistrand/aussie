package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OidcTokenExchangeResponse")
class OidcTokenExchangeResponseTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should throw on null access token")
        void shouldThrowOnNullAccessToken() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeResponse(
                            null, Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("Should throw on blank access token")
        void shouldThrowOnBlankAccessToken() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OidcTokenExchangeResponse(
                            "  ", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("Should default tokenType to Bearer when null")
        void shouldDefaultTokenType() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.empty(), null, 3600, Optional.empty(), Map.of());
            assertEquals("Bearer", response.tokenType());
        }

        @Test
        @DisplayName("Should default tokenType to Bearer when blank")
        void shouldDefaultTokenTypeWhenBlank() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.empty(), "  ", 3600, Optional.empty(), Map.of());
            assertEquals("Bearer", response.tokenType());
        }

        @Test
        @DisplayName("Should default expiresIn to 3600 when zero or negative")
        void shouldDefaultExpiresIn() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.empty(), "Bearer", 0, Optional.empty(), Map.of());
            assertEquals(3600L, response.expiresIn());
        }

        @Test
        @DisplayName("Should default null optional fields to empty")
        void shouldDefaultNullOptionals() {
            var response = new OidcTokenExchangeResponse("token", null, null, "Bearer", 3600, null, null);
            assertEquals(Optional.empty(), response.idToken());
            assertEquals(Optional.empty(), response.refreshToken());
            assertEquals(Optional.empty(), response.scope());
            assertEquals(Map.of(), response.additionalClaims());
        }
    }

    @Nested
    @DisplayName("Token presence checks")
    class TokenPresence {

        @Test
        @DisplayName("hasIdToken should return true when present")
        void shouldDetectIdToken() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.of("id-token"), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            assertTrue(response.hasIdToken());
        }

        @Test
        @DisplayName("hasIdToken should return false when absent")
        void shouldDetectNoIdToken() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            assertFalse(response.hasIdToken());
        }

        @Test
        @DisplayName("hasRefreshToken should return true when present")
        void shouldDetectRefreshToken() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.of("refresh"), "Bearer", 3600, Optional.empty(), Map.of());
            assertTrue(response.hasRefreshToken());
        }

        @Test
        @DisplayName("hasRefreshToken should return false when absent")
        void shouldDetectNoRefreshToken() {
            var response = new OidcTokenExchangeResponse(
                    "token", Optional.empty(), Optional.empty(), "Bearer", 3600, Optional.empty(), Map.of());
            assertFalse(response.hasRefreshToken());
        }
    }
}
