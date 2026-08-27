package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.net.SocketAddress;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.http.UpstreamAddressResolver;
import aussie.core.config.OidcConfig;
import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeRequest.ClientAuthMethod;
import aussie.core.port.out.OutboundHttpClients;

class DefaultOidcTokenExchangeProviderLogRedactionTest {

    private final Logger logger = Logger.getLogger(DefaultOidcTokenExchangeProvider.class.getName());
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
    void doesNotLogAuthorizationCodeOrProviderResponse() {
        var webClient = mock(WebClient.class);
        var request = mock(HttpRequest.class);
        var response = mock(HttpResponse.class);
        var clients = mock(OutboundHttpClients.class);
        var config = mock(OidcConfig.class);
        var tokenExchange = mock(OidcConfig.TokenExchangeConfig.class);
        var resolver = mock(UpstreamAddressResolver.class);
        lenient().when(config.tokenExchange()).thenReturn(tokenExchange);
        lenient().when(tokenExchange.timeout()).thenReturn(Duration.ofSeconds(1));
        when(clients.webClient()).thenReturn(webClient);
        when(resolver.resolve(any(URI.class)))
                .thenReturn(Uni.createFrom().item(io.vertx.core.net.SocketAddress.inetSocketAddress(443, "192.0.2.1")));
        when(webClient.requestAbs(any(HttpMethod.class), any(SocketAddress.class), anyString()))
                .thenReturn(request);
        when(request.ssl(true)).thenReturn(request);
        when(request.followRedirects(false)).thenReturn(request);
        when(request.timeout(1000)).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
        when(request.sendBuffer(any(Buffer.class))).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(400);
        when(response.bodyAsString()).thenReturn("provider-response-secret");
        var provider = new DefaultOidcTokenExchangeProvider(clients, config, resolver);

        var requestData = new OidcTokenExchangeRequest(
                "authorization-code-secret",
                "https://app.example/callback",
                Optional.empty(),
                "https://idp.example/token",
                "client",
                null,
                ClientAuthMethod.CLIENT_SECRET_BASIC,
                Optional.empty());

        assertThrows(
                RuntimeException.class,
                () -> provider.exchange(requestData).await().atMost(Duration.ofSeconds(2)));

        var output = handler.text();
        assertTrue(output.contains("400"), "expected to capture the provider status log");
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("authorization-code-secret"));
        org.junit.jupiter.api.Assertions.assertFalse(output.contains("provider-response-secret"));
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
