package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.port.out.TranslationMetrics;
import aussie.core.util.SafeLogging;
import aussie.spi.TokenTranslatorProvider;

class TokenTranslationLogRedactionTest {

    private final Logger logger = Logger.getLogger(TokenTranslationService.class.getName());
    private final CapturingHandler handler = new CapturingHandler();
    private TokenTranslatorProvider provider;
    private TokenTranslationService service;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        var config = mock(TokenTranslationConfig.class);
        var cacheConfig = mock(TokenTranslationConfig.Cache.class);
        var registry = mock(TokenTranslatorProviderRegistry.class);
        provider = mock(TokenTranslatorProvider.class);

        when(config.cache()).thenReturn(cacheConfig);
        when(cacheConfig.ttlSeconds()).thenReturn(300);
        when(cacheConfig.maxSize()).thenReturn(100L);
        when(registry.getProvider()).thenReturn(provider);
        when(provider.name()).thenReturn("test-provider");

        service = new TokenTranslationService(config, registry, mock(TranslationMetrics.class));
        service.init();
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
    void doesNotLogSubjectIssuerClaimsOrProviderResponse() {
        final var issuer = "issuer-secret.example";
        final var subject = "subject-secret";
        final var claim = "claim-secret";
        when(provider.translate(issuer, subject, Map.of("jti", claim)))
                .thenReturn(Uni.createFrom().item(new TranslatedClaims(Set.of("admin"), Set.of("read"), Map.of())));

        service.translate(issuer, subject, Map.of("jti", claim)).await().atMost(Duration.ofSeconds(1));

        var output = handler.text();
        assertTrue(output.contains(SafeLogging.identifier(subject)), "expected to capture the redacted subject");
        assertFalse(output.contains(issuer));
        assertFalse(output.contains(subject));
        assertFalse(output.contains(claim));
    }

    @Test
    void doesNotLogProviderFailureMessage() {
        final var secret = "provider-response-secret";
        when(provider.translate("issuer", "subject", Map.of("jti", "token")))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException(secret)));

        assertThrows(RuntimeException.class, () -> service.translate("issuer", "subject", Map.of("jti", "token"))
                .await()
                .atMost(Duration.ofSeconds(1)));

        assertTrue(handler.text().contains("IllegalStateException"), "expected to capture the redacted error type");
        assertFalse(handler.text().contains(secret));
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
