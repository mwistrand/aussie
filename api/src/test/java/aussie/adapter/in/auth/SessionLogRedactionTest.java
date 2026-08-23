package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.common.context.ClientContext;
import aussie.core.config.ApiKeyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;

@DisplayName("Session log redaction")
class SessionLogRedactionTest {

    private static final String RAW_SESSION_ID = "super-secret-session-cookie-value-do-not-leak";
    private static final String NOOP_PROPERTY = "aussie.auth.dangerous-noop";

    private CredentialAuthenticationMechanism mechanism;
    private RoutingContext routingContext;
    private HttpServerRequest httpRequest;
    private SessionCookieManager cookieManager;
    private SessionManagement sessionManagement;
    private IdentityProviderManager identityProviderManager;

    private Logger jul;
    private Level previousLevel;
    private CapturingHandler handler;
    private String previousNoopProperty;

    @BeforeEach
    void setUp() {
        previousNoopProperty = System.setProperty(NOOP_PROPERTY, "false");
        var config = mock(SessionConfig.class);
        cookieManager = mock(SessionCookieManager.class);
        sessionManagement = mock(SessionManagement.class);
        var metrics = mock(GatewayMetrics.class);
        var securityMonitor = mock(SecurityMonitor.class);
        var clientContextResolver = mock(ClientContextResolver.class);
        var apiKeyConfig = mock(ApiKeyConfig.class);
        identityProviderManager = mock(IdentityProviderManager.class);
        routingContext = mock(RoutingContext.class);
        httpRequest = mock(HttpServerRequest.class);

        when(routingContext.request()).thenReturn(httpRequest);
        var headers = mock(MultiMap.class);
        when(httpRequest.headers()).thenReturn(headers);
        when(headers.getAll("Authorization")).thenReturn(List.of());
        when(httpRequest.path()).thenReturn("/api/test");
        when(config.enabled()).thenReturn(true);
        when(config.slidingExpiration()).thenReturn(false);
        when(cookieManager.hasSessionCookie(httpRequest)).thenReturn(true);
        when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext("127.0.0.1", false, null));

        mechanism = new CredentialAuthenticationMechanism(
                mock(aussie.core.service.auth.TokenValidationService.class),
                config,
                cookieManager,
                sessionManagement,
                metrics,
                securityMonitor,
                clientContextResolver,
                apiKeyConfig);

        jul = Logger.getLogger(CredentialAuthenticationMechanism.class.getName());
        previousLevel = jul.getLevel();
        jul.setLevel(Level.ALL);
        handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        jul.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        if (previousNoopProperty == null) {
            System.clearProperty(NOOP_PROPERTY);
        } else {
            System.setProperty(NOOP_PROPERTY, previousNoopProperty);
        }
        if (jul != null && handler != null) {
            jul.removeHandler(handler);
            jul.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("never logs the raw session cookie value, even at DEBUG")
    void neverLogsRawSessionCookie() {
        when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of(RAW_SESSION_ID));
        when(sessionManagement.getSession(RAW_SESSION_ID))
                .thenReturn(Uni.createFrom().item(Optional.of(testSession())));

        mechanism.authenticate(routingContext, identityProviderManager).await().atMost(Duration.ofSeconds(1));

        var captured = handler.snapshot();
        assertFalse(captured.isEmpty(), "expected the mechanism to emit at least one log line");
        for (String line : captured) {
            assertFalse(line.contains(RAW_SESSION_ID), "log line leaked raw session id: " + line);
        }
    }

    @Test
    @DisplayName("never logs the raw session cookie value when session is invalid")
    void neverLogsRawSessionCookieOnInvalid() {
        when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of(RAW_SESSION_ID));
        when(sessionManagement.getSession(RAW_SESSION_ID))
                .thenReturn(Uni.createFrom().item(Optional.empty()));

        assertThrows(AuthenticationFailedException.class, () -> mechanism
                .authenticate(routingContext, identityProviderManager)
                .await()
                .atMost(Duration.ofSeconds(1)));

        for (String line : handler.snapshot()) {
            assertFalse(line.contains(RAW_SESSION_ID), "log line leaked raw session id: " + line);
        }
    }

    @Test
    @DisplayName("emits a hashed identifier instead of the raw cookie")
    void emitsHashedIdentifier() {
        when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of(RAW_SESSION_ID));
        when(sessionManagement.getSession(RAW_SESSION_ID))
                .thenReturn(Uni.createFrom().item(Optional.of(testSession())));

        mechanism.authenticate(routingContext, identityProviderManager).await().atMost(Duration.ofSeconds(1));

        var captured = handler.snapshot();
        assertTrue(
                captured.stream().anyMatch(line -> line.contains("hash=")),
                "expected a hash= marker in captured logs, got: " + captured);
    }

    private Session testSession() {
        return new Session(
                RAW_SESSION_ID,
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1"),
                Set.of("admin"),
                Instant.now(),
                Instant.now().plusSeconds(28800),
                Instant.now(),
                "test-agent",
                "127.0.0.1");
    }

    private static final class CapturingHandler extends Handler {
        private final List<String> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            String msg = record.getMessage();
            if (record.getParameters() != null && record.getParameters().length > 0) {
                try {
                    msg = String.format(msg, record.getParameters());
                } catch (RuntimeException ignored) {
                    // fall back to raw message + params concatenation if format fails
                    StringBuilder sb = new StringBuilder(record.getMessage());
                    for (Object p : record.getParameters()) {
                        sb.append('|').append(p);
                    }
                    msg = sb.toString();
                }
            }
            records.add(msg);
        }

        synchronized List<String> snapshot() {
            return new ArrayList<>(records);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
    }
}
