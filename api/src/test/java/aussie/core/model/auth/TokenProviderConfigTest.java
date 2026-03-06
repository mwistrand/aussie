package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
                    () -> new TokenProviderConfig(
                            null, "issuer", JWKS_URI, Optional.empty(), Set.of(), Duration.ofHours(1), Map.of()));
        }

        @Test
        @DisplayName("Should throw on blank issuer")
        void shouldThrowOnBlankIssuer() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenProviderConfig(
                            "id", "  ", JWKS_URI, Optional.empty(), Set.of(), Duration.ofHours(1), Map.of()));
        }

        @Test
        @DisplayName("Should throw on null jwksUri")
        void shouldThrowOnNullJwksUri() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new TokenProviderConfig(
                            "id", "issuer", null, Optional.empty(), Set.of(), Duration.ofHours(1), Map.of()));
        }
    }

    @Nested
    @DisplayName("Constructor defaults")
    class ConstructorDefaults {

        @Test
        @DisplayName("Should default null optional fields to sensible values")
        void shouldDefaultNullFields() {
            var config = new TokenProviderConfig("id", "issuer", JWKS_URI, null, null, null, null);

            assertEquals(Optional.empty(), config.discoveryUri());
            assertEquals(Set.of(), config.audiences());
            assertEquals(Duration.ofHours(1), config.keyRefreshInterval());
            assertEquals(Map.of(), config.claimsMapping());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build config with all optional fields")
        void shouldBuildWithAllFields() {
            var discoveryUri = URI.create("https://example.com/.well-known/openid-configuration");
            var config = TokenProviderConfig.builder("auth0", "https://auth0.example.com", JWKS_URI)
                    .discoveryUri(discoveryUri)
                    .audiences(Set.of("api"))
                    .keyRefreshInterval(Duration.ofMinutes(30))
                    .claimsMapping(Map.of("sub", "userId"))
                    .build();

            assertEquals("auth0", config.id());
            assertEquals(Optional.of(discoveryUri), config.discoveryUri());
            assertEquals(Set.of("api"), config.audiences());
            assertEquals(Duration.ofMinutes(30), config.keyRefreshInterval());
            assertEquals(Map.of("sub", "userId"), config.claimsMapping());
        }
    }
}
