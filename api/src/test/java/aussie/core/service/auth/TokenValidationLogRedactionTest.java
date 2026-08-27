package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.util.SafeLogging;
import aussie.spi.TokenValidatorProvider;

class TokenValidationLogRedactionTest {

    private final Logger logger = Logger.getLogger(TokenValidationService.class.getName());
    private final CapturingHandler handler = new CapturingHandler();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotLogIssuerSubjectOrJti() {
        var validatorInstances = mock(Instance.class);
        var config = mock(RouteAuthConfig.class);
        var provider = mock(TokenValidatorProvider.class);
        var revocation = mock(TokenRevocationService.class);
        var providerProperties = mock(RouteAuthConfig.TokenProviderProperties.class);
        final var issuer = "issuer-secret";
        final var subject = "subject-secret";
        final var jti = "jti-secret";

        when(config.enabled()).thenReturn(true);
        when(config.providers()).thenReturn(Map.of("provider", providerProperties));
        when(providerProperties.issuer()).thenReturn(issuer);
        when(providerProperties.jwksUri()).thenReturn("https://issuer.example/jwks");
        when(providerProperties.discoveryUri()).thenReturn(java.util.Optional.empty());
        when(providerProperties.audiences()).thenReturn(java.util.Set.of("audience"));
        when(providerProperties.allowedAlgorithms()).thenReturn(java.util.Set.of("RS256"));
        when(providerProperties.keyRefreshInterval()).thenReturn(Duration.ofMinutes(5));
        when(providerProperties.claimsMapping()).thenReturn(Map.of());
        when(validatorInstances.stream()).thenReturn(Stream.of(provider));
        when(provider.isAvailable()).thenReturn(true);
        when(provider.priority()).thenReturn(100);
        when(provider.name()).thenReturn("validator");
        when(provider.validate(anyString(), any(TokenProviderConfig.class)))
                .thenReturn(Uni.createFrom()
                        .item(new TokenValidationResult.Valid(
                                subject,
                                issuer,
                                Map.of("jti", jti, "iat", Instant.now().getEpochSecond()),
                                Instant.now().plusSeconds(600))));
        when(revocation.isEnabled()).thenReturn(true);
        when(revocation.isRevoked(anyString(), anyString(), any(), any()))
                .thenReturn(Uni.createFrom().item(true));

        new TokenValidationService(validatorInstances, config, revocation)
                .validate("token-secret")
                .await()
                .atMost(Duration.ofSeconds(1));

        var output = handler.text();
        assertTrue(output.contains(SafeLogging.identifier(jti)), "expected to capture the redacted JTI");
        assertFalse(output.contains(issuer));
        assertFalse(output.contains(subject));
        assertFalse(output.contains(jti));
        assertFalse(output.contains("token-secret"));
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            var text = new StringBuilder(record.getMessage());
            if (record.getParameters() != null) {
                for (var parameter : record.getParameters()) {
                    text.append('|').append(parameter);
                }
            }
            if (record.getThrown() != null) {
                text.append('|').append(record.getThrown().getMessage());
            }
            records.add(text.toString());
        }

        synchronized String text() {
            return String.join("\n", records);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
    }
}
