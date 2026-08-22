package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.spi.TokenValidatorProvider;

@DisplayName("TokenValidationService")
@SuppressWarnings("unchecked")
class TokenValidationServiceTest {

    private Instance<TokenValidatorProvider> validatorInstances;
    private RouteAuthConfig config;
    private TokenRevocationService revocationService;

    @BeforeEach
    void setUp() {
        validatorInstances = mock(Instance.class);
        config = mock(RouteAuthConfig.class);
        revocationService = mock(TokenRevocationService.class);
        when(config.providers()).thenReturn(Map.of());
    }

    private TokenValidationService createService(boolean enabled, TokenValidatorProvider... validators) {
        when(config.enabled()).thenReturn(enabled);
        when(validatorInstances.stream()).thenReturn(Stream.of(validators));
        return new TokenValidationService(validatorInstances, config, revocationService);
    }

    private TokenValidatorProvider createValidator(String name, int priority) {
        var validator = mock(TokenValidatorProvider.class);
        when(validator.name()).thenReturn(name);
        when(validator.priority()).thenReturn(priority);
        when(validator.isAvailable()).thenReturn(true);
        return validator;
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when enabled")
        void shouldReturnTrueWhenEnabled() {
            var service = createService(true);
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when disabled")
        void shouldReturnFalseWhenDisabled() {
            var service = createService(false);
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("should return NoToken when disabled")
        void shouldReturnNoTokenWhenDisabled() {
            var service = createService(false);

            var result = service.validate("some-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
        }

        @Test
        @DisplayName("should return NoToken when token is null")
        void shouldReturnNoTokenWhenTokenIsNull() {
            var service = createService(true);

            var result = service.validate(null).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
        }

        @Test
        @DisplayName("should return NoToken when token is blank")
        void shouldReturnNoTokenWhenTokenIsBlank() {
            var service = createService(true);

            var result = service.validate("   ").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
        }

        @Test
        @DisplayName("should return Invalid when no providers configured")
        void shouldReturnInvalidWhenNoProviders() {
            var service = createService(true);

            var result = service.validate("some-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            assertEquals("No token providers configured", ((TokenValidationResult.Invalid) result).reason());
        }

        @Test
        @DisplayName("should validate token with provider and validator")
        void shouldValidateTokenWithProviderAndValidator() {
            var validator = createValidator("oidc", 100);
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var validResult = new TokenValidationResult.Valid(
                    "user-1",
                    "https://issuer.example.com",
                    Map.of("sub", "user-1"),
                    Instant.now().plusSeconds(3600));
            when(validator.validate(anyString(), any(TokenProviderConfig.class)))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(revocationService.isEnabled()).thenReturn(false);

            var service = createService(true, validator);
            var result = service.validate("valid-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            assertEquals("user-1", ((TokenValidationResult.Valid) result).subject());
        }

        @Test
        @DisplayName("should return Invalid when all validators reject token")
        void shouldReturnInvalidWhenAllValidatorsReject() {
            var validator = createValidator("oidc", 100);
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            when(validator.validate(anyString(), any(TokenProviderConfig.class)))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

            var service = createService(true, validator);
            var result = service.validate("invalid-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
        }

        @Test
        @DisplayName("should check revocation for valid tokens")
        void shouldCheckRevocationForValidTokens() {
            var validator = createValidator("oidc", 100);
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var expiresAt = Instant.now().plusSeconds(3600);
            var validResult = new TokenValidationResult.Valid(
                    "user-1",
                    "https://issuer.example.com",
                    Map.of(
                            "sub",
                            "user-1",
                            "jti",
                            "token-123",
                            "iat",
                            Instant.now().getEpochSecond()),
                    expiresAt);
            when(validator.validate(anyString(), any(TokenProviderConfig.class)))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(revocationService.isEnabled()).thenReturn(true);
            when(revocationService.isRevoked(anyString(), anyString(), any(Instant.class), any(Instant.class)))
                    .thenReturn(Uni.createFrom().item(true));

            var service = createService(true, validator);
            var result = service.validate("revoked-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            verify(revocationService).isRevoked(anyString(), anyString(), any(Instant.class), any(Instant.class));
        }

        @Test
        @DisplayName("should reject tokens without trustworthy revocation claims")
        void shouldRejectInvalidRevocationClaims() {
            final var validator = createValidator("oidc", 100);
            final var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));
            when(revocationService.isEnabled()).thenReturn(true);

            final var service = createService(true, validator);
            final var invalidClaims = List.<Map<String, Object>>of(
                    Map.of("iat", Instant.now().getEpochSecond()),
                    Map.of("jti", "token-123"),
                    Map.of("jti", "   ", "iat", Instant.now().getEpochSecond()),
                    Map.of("jti", 123, "iat", Instant.now().getEpochSecond()),
                    Map.of("jti", "token-123", "iat", "not-a-timestamp"),
                    Map.of("jti", "token-123", "iat", Long.MAX_VALUE),
                    Map.of(
                            "jti",
                            "token-123",
                            "iat",
                            Instant.now().plusSeconds(60).getEpochSecond()));

            for (final var claims : invalidClaims) {
                final var valid = new TokenValidationResult.Valid(
                        "user-1",
                        "https://issuer.example.com",
                        claims,
                        Instant.now().plusSeconds(3600));
                when(validator.validate(anyString(), any(TokenProviderConfig.class)))
                        .thenReturn(Uni.createFrom().item(valid));

                assertInstanceOf(
                        TokenValidationResult.Invalid.class,
                        service.validate("token").await().atMost(Duration.ofSeconds(1)));
            }
            verify(revocationService, never()).isRevoked(any(), any(), any(), any());
        }

        @Test
        @DisplayName("should skip revocation when disabled")
        void shouldSkipRevocationWhenDisabled() {
            var validator = createValidator("oidc", 100);
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var validResult = new TokenValidationResult.Valid(
                    "user-1",
                    "https://issuer.example.com",
                    Map.of("sub", "user-1"),
                    Instant.now().plusSeconds(3600));
            when(validator.validate(anyString(), any(TokenProviderConfig.class)))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(revocationService.isEnabled()).thenReturn(false);

            var service = createService(true, validator);
            var result = service.validate("valid-token").await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            verify(revocationService, never()).isRevoked(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("startup validation")
    class StartupValidationTests {

        @Test
        @DisplayName("should fail fast when a provider has no audiences configured")
        void shouldThrowWhenProviderHasNoAudiences() {
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of());
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var ex = assertThrows(IllegalStateException.class, () -> createService(true));
            assertTrue(
                    ex.getMessage().contains("audiences"),
                    "error must call out the missing audiences config, got: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("provider1"),
                    "error must identify the offending provider, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("should fail fast when a provider has no allowed algorithms configured")
        void shouldThrowWhenProviderHasNoAllowedAlgorithms() {
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of());
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var ex = assertThrows(IllegalStateException.class, () -> createService(true));
            assertTrue(
                    ex.getMessage().contains("allowed algorithms")
                            || ex.getMessage().contains("allowed-algorithms"),
                    "error must call out the missing algorithms config, got: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("provider1"),
                    "error must identify the offending provider, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("should not validate provider configs when route auth is disabled")
        void shouldSkipValidationWhenDisabled() {
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.audiences()).thenReturn(Set.of());
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of());
            when(config.providers()).thenReturn(Map.of("provider1", providerProps));

            var service = createService(false);
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("getProviderConfig()")
    class GetProviderConfigTests {

        @Test
        @DisplayName("should return null for unknown provider")
        void shouldReturnNullForUnknownProvider() {
            var service = createService(true);
            assertNull(service.getProviderConfig("unknown"));
        }

        @Test
        @DisplayName("should return config for loaded provider")
        void shouldReturnConfigForLoadedProvider() {
            var providerProps = mock(RouteAuthConfig.TokenProviderProperties.class);
            when(providerProps.issuer()).thenReturn("https://issuer.example.com");
            when(providerProps.jwksUri()).thenReturn("https://issuer.example.com/.well-known/jwks.json");
            when(providerProps.discoveryUri()).thenReturn(Optional.empty());
            when(providerProps.audiences()).thenReturn(Set.of("aussie-gateway"));
            when(providerProps.allowedAlgorithms()).thenReturn(Set.of("RS256"));
            when(providerProps.keyRefreshInterval()).thenReturn(Duration.ofHours(1));
            when(providerProps.claimsMapping()).thenReturn(Map.of());
            when(config.providers()).thenReturn(Map.of("myProvider", providerProps));

            var service = createService(true);
            var providerConfig = service.getProviderConfig("myProvider");

            assertNotNull(providerConfig);
            assertEquals("https://issuer.example.com", providerConfig.issuer());
        }
    }
}
