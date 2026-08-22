package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.service.common.SourceIdentifierExtractor;
import aussie.core.service.common.TrustedProxyValidator;

/**
 * Vert.x overload parity tests for {@link SourceIdentifierExtractor}. The
 * shared parsing logic is covered exhaustively by
 * {@link SourceIdentifierExtractorTest} on the JAX-RS overload; this suite
 * verifies that the Vert.x entry point feeds the same code path.
 */
@DisplayName("SourceIdentifierExtractor (Vert.x overload)")
@ExtendWith(MockitoExtension.class)
class SourceIdentifierExtractorVertxTest {

    @Mock
    private HttpServerRequest request;

    private SourceIdentifierExtractor extractor;

    @BeforeEach
    void setUp() {
        // Parser-focused tests use an explicitly trusted request boundary.
        var validator = mock(TrustedProxyValidator.class);
        when(validator.shouldTrustForwardingHeaders(nullable(String.class))).thenReturn(true);
        extractor = new SourceIdentifierExtractor(validator);
    }

    @Nested
    @DisplayName("IP extraction")
    class Ip {

        @Test
        @DisplayName("reads first IP from X-Forwarded-For")
        void readsXForwardedFor() {
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 192.168.1.1");

            var result = extractor.extract(request, null);

            assertEquals("203.0.113.50", result.ipAddress());
        }

        @Test
        @DisplayName("reads RFC 7239 Forwarded for= when X-Forwarded-For absent")
        void readsForwardedHeader() {
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn("for=198.51.100.7;proto=https");

            var result = extractor.extract(request, null);

            assertEquals("198.51.100.7", result.ipAddress());
        }

        @Test
        @DisplayName("reads X-Real-IP as last header fallback")
        void readsXRealIp() {
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn(null);
            when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.99");

            var result = extractor.extract(request, null);

            assertEquals("198.51.100.99", result.ipAddress());
        }

        @Test
        @DisplayName("falls back to the supplied socket IP when no proxy headers")
        void fallsBackToSocketIp() {
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn(null);
            when(request.getHeader("X-Real-IP")).thenReturn(null);

            var result = extractor.extract(request, "10.0.0.42");

            assertEquals("10.0.0.42", result.ipAddress());
        }

        @Test
        @DisplayName("falls back to authority host when no headers and no socket IP")
        void fallsBackToAuthorityHost() {
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn(null);
            when(request.getHeader("X-Real-IP")).thenReturn(null);
            when(request.authority()).thenReturn(HostAndPort.create("example.internal", 8080));

            var result = extractor.extract(request, null);

            assertEquals("example.internal", result.ipAddress());
        }
    }

    @Nested
    @DisplayName("Host extraction")
    class Host {

        @Test
        @DisplayName("reads X-Forwarded-Host first")
        void readsXForwardedHost() {
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");
            when(request.getHeader("X-Forwarded-Host")).thenReturn("public.example.com");

            var result = extractor.extract(request, null);

            assertTrue(result.host().isPresent());
            assertEquals("public.example.com", result.host().get());
        }

        @Test
        @DisplayName("strips port from the Host header fallback")
        void stripsPortFromHostHeader() {
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50");
            when(request.getHeader("X-Forwarded-Host")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn(null);
            when(request.getHeader("Host")).thenReturn("api.example.com:8443");

            var result = extractor.extract(request, null);

            assertEquals(Optional.of("api.example.com"), result.host());
        }
    }

    @Nested
    @DisplayName("Forwarded-For chain")
    class ForwardedForChain {

        @Test
        @DisplayName("propagates the full chain to SourceIdentifier.forwardedFor")
        void forwardedForChain() {
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.50, 192.168.1.1, 10.0.0.1");

            var result = extractor.extract(request, null);

            assertEquals(Optional.of("203.0.113.50, 192.168.1.1, 10.0.0.1"), result.forwardedFor());
        }

        @Test
        @DisplayName("forwardedFor is empty when no header is present")
        void forwardedForAbsent() {
            when(request.getHeader("X-Forwarded-For")).thenReturn(null);
            when(request.getHeader("Forwarded")).thenReturn(null);
            when(request.getHeader("X-Real-IP")).thenReturn(null);

            var result = extractor.extract(request, "10.0.0.1");

            assertFalse(result.forwardedFor().isPresent());
        }
    }
}
