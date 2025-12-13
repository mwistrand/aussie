package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.config.RateLimitingConfig;
import aussie.core.model.EffectiveRateLimit;
import aussie.core.model.RateLimitDecision;
import aussie.core.model.RateLimitKey;
import aussie.core.model.RateLimitKeyType;
import aussie.core.port.out.RateLimiter;

@DisplayName("WebSocketRateLimitService")
class WebSocketRateLimitServiceTest {

    private WebSocketRateLimitService service;
    private RateLimiter rateLimiter;
    private RateLimitingConfig config;
    private RateLimitResolver rateLimitResolver;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        config = mock(RateLimitingConfig.class);
        rateLimitResolver = mock(RateLimitResolver.class);

        // Setup default config
        when(config.enabled()).thenReturn(true);
        when(rateLimiter.isEnabled()).thenReturn(true);

        var wsConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.class);
        var connConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig.class);
        var msgConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.MessageConfig.class);
        when(config.websocket()).thenReturn(wsConfig);
        when(wsConfig.connection()).thenReturn(connConfig);
        when(wsConfig.message()).thenReturn(msgConfig);
        when(connConfig.enabled()).thenReturn(true);
        when(msgConfig.enabled()).thenReturn(true);

        // Default limits
        var defaultLimit = new EffectiveRateLimit(100, 60, 100);
        when(rateLimitResolver.resolveWebSocketConnectionLimit(any())).thenReturn(defaultLimit);
        when(rateLimitResolver.resolveWebSocketMessageLimit(any())).thenReturn(defaultLimit);

        service = new WebSocketRateLimitService(rateLimiter, config, rateLimitResolver);
    }

    @Nested
    @DisplayName("checkConnectionLimit")
    class CheckConnectionLimit {

        @Test
        @DisplayName("should check rate limit for WebSocket connection")
        void shouldCheckRateLimitForConnection() {
            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            var result = service.checkConnectionLimit("test-service", "client-123")
                    .await()
                    .indefinitely();

            assertTrue(result.allowed());

            var keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            var capturedKey = keyCaptor.getValue();
            assertEquals(RateLimitKeyType.WS_CONNECTION, capturedKey.keyType());
            assertEquals("client-123", capturedKey.clientId());
            assertEquals("test-service", capturedKey.serviceId());
        }

        @Test
        @DisplayName("should return allow when connection rate limiting is disabled")
        void shouldReturnAllowWhenConnectionRateLimitingDisabled() {
            var connConfig = config.websocket().connection();
            when(connConfig.enabled()).thenReturn(false);

            var result = service.checkConnectionLimit("test-service", "client-123")
                    .await()
                    .indefinitely();

            assertTrue(result.allowed());
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }

        @Test
        @DisplayName("should return rejected decision when limit exceeded")
        void shouldReturnRejectedWhenLimitExceeded() {
            var decision =
                    RateLimitDecision.rejected(100, 60, java.time.Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            var result = service.checkConnectionLimit("test-service", "client-123")
                    .await()
                    .indefinitely();

            assertFalse(result.allowed());
        }
    }

    @Nested
    @DisplayName("checkMessageLimit")
    class CheckMessageLimit {

        @Test
        @DisplayName("should check rate limit for WebSocket message")
        void shouldCheckRateLimitForMessage() {
            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            var result = service.checkMessageLimit("test-service", "client-123", "conn-456")
                    .await()
                    .indefinitely();

            assertTrue(result.allowed());

            var keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            var capturedKey = keyCaptor.getValue();
            assertEquals(RateLimitKeyType.WS_MESSAGE, capturedKey.keyType());
            assertEquals("client-123", capturedKey.clientId());
            assertEquals("test-service", capturedKey.serviceId());
            assertEquals(Optional.of("conn-456"), capturedKey.endpointId());
        }

        @Test
        @DisplayName("should return allow when message rate limiting is disabled")
        void shouldReturnAllowWhenMessageRateLimitingDisabled() {
            var msgConfig = config.websocket().message();
            when(msgConfig.enabled()).thenReturn(false);

            var result = service.checkMessageLimit("test-service", "client-123", "conn-456")
                    .await()
                    .indefinitely();

            assertTrue(result.allowed());
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }
    }

    @Nested
    @DisplayName("cleanupConnection")
    class CleanupConnection {

        @Test
        @DisplayName("should call removeKeysMatching with correct pattern")
        void shouldCallRemoveKeysMatchingWithCorrectPattern() {
            when(rateLimiter.removeKeysMatching(any()))
                    .thenReturn(Uni.createFrom().voidItem());

            service.cleanupConnection("test-service", "client-123", "conn-456")
                    .await()
                    .indefinitely();

            verify(rateLimiter).removeKeysMatching(eq("ws_message:client-123:test-service:conn-456"));
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return true when config and rate limiter are enabled")
        void shouldReturnTrueWhenBothEnabled() {
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when config is disabled")
        void shouldReturnFalseWhenConfigDisabled() {
            when(config.enabled()).thenReturn(false);
            assertFalse(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when rate limiter is disabled")
        void shouldReturnFalseWhenRateLimiterDisabled() {
            when(rateLimiter.isEnabled()).thenReturn(false);
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("isConnectionRateLimitEnabled")
    class IsConnectionRateLimitEnabled {

        @Test
        @DisplayName("should return true when enabled")
        void shouldReturnTrueWhenEnabled() {
            assertTrue(service.isConnectionRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when connection config is disabled")
        void shouldReturnFalseWhenConnectionConfigDisabled() {
            var connConfig = config.websocket().connection();
            when(connConfig.enabled()).thenReturn(false);
            assertFalse(service.isConnectionRateLimitEnabled());
        }
    }

    @Nested
    @DisplayName("isMessageRateLimitEnabled")
    class IsMessageRateLimitEnabled {

        @Test
        @DisplayName("should return true when enabled")
        void shouldReturnTrueWhenEnabled() {
            assertTrue(service.isMessageRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when message config is disabled")
        void shouldReturnFalseWhenMessageConfigDisabled() {
            var msgConfig = config.websocket().message();
            when(msgConfig.enabled()).thenReturn(false);
            assertFalse(service.isMessageRateLimitEnabled());
        }
    }
}
