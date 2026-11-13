package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TokenProviderConfig")
class TokenProviderConfigTest {

    private static final URI JWKS_URI = URI.create("https://example.com/.well-known/jwks.json");

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Should throw on null id")
        void shouldThrowOnNullId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenProviderConfig(null, "issuer", JWKS_URI, Set.of(), Set.of("RS256"), Map.of()));
        }

        @Test
        @DisplayName("Should throw on blank issuer")
        void shouldThrowOnBlankIssuer() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenProviderConfig("id", "  ", JWKS_URI, Set.of(), Set.of("RS256"), Map.of()));
        }

        @Test
        @DisplayName("Should throw on null jwksUri")
        void shouldThrowOnNullJwksUri() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenProviderConfig("id", "issuer", null, Set.of(), Set.of("RS256"), Map.of()));
        }
    }

    @Nested
    @DisplayName("Constructor defaults")
    class ConstructorDefaults {

        @Test
        @DisplayName("Should default null optional fields to sensible values")
        void shouldDefaultNullFields() {
            var config = new TokenProviderConfig("id", "issuer", JWKS_URI, null, null, null);

            assertEquals(Set.of(), config.audiences());
            assertEquals(Map.of(), config.claimsMapping());
            // Default algorithm whitelist is the asymmetric set; HS* is excluded by default.
            assertEquals(
                    Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "EdDSA"), config.allowedAlgorithms());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build config with all optional fields")
        void shouldBuildWithAllFields() {
            var config = TokenProviderConfig.builder("auth0", "https://auth0.example.com", JWKS_URI)
                    .audiences(Set.of("api"))
                    .allowedAlgorithms(Set.of("RS256"))
                    .claimsMapping(Map.of("sub", "userId"))
                    .build();

            assertEquals("auth0", config.id());
            assertEquals(Set.of("api"), config.audiences());
            assertEquals(Set.of("RS256"), config.allowedAlgorithms());
            assertEquals(Map.of("sub", "userId"), config.claimsMapping());
        }
    }
}
