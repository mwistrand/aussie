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

import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.ratelimit.RateLimitKeyType;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.*;
import aussie.core.service.routing.*;

@DisplayName("WebSocketRateLimitService")
class WebSocketRateLimitServiceTest {

    private WebSocketRateLimitService service;
    private RateLimiter rateLimiter;
    private RateLimitingConfig config;
    private RateLimitResolver rateLimitResolver;
    private ServiceRegistry serviceRegistry;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        config = mock(RateLimitingConfig.class);
        rateLimitResolver = mock(RateLimitResolver.class);
        serviceRegistry = mock(ServiceRegistry.class);

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
        when(rateLimitResolver.resolveWebSocketMessageLimit(any())).thenReturn(defaultLimit);

        // Default service lookup returns empty (use platform defaults)
        when(serviceRegistry.getServiceForRateLimiting(any()))
                .thenReturn(Uni.createFrom().item(Optional.empty()));

        service = new WebSocketRateLimitService(rateLimiter, config, rateLimitResolver, serviceRegistry);
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

        @Test
        @DisplayName("should return rejected decision when limit exceeded")
        void shouldReturnRejectedWhenLimitExceeded() {
            var decision =
                    RateLimitDecision.rejected(100, 60, java.time.Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            var result = service.checkMessageLimit("test-service", "client-123", "conn-456")
                    .await()
                    .indefinitely();

            assertFalse(result.allowed());
        }

        @Test
        @DisplayName("should look up service via ServiceRegistry")
        void shouldLookUpServiceViaServiceRegistry() {
            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            service.checkMessageLimit("my-service", "client-123", "conn-456")
                    .await()
                    .indefinitely();

            verify(serviceRegistry).getServiceForRateLimiting("my-service");
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

    @Nested
    @DisplayName("WS_CLOSE_CODE_RATE_LIMITED constant")
    class CloseCodeConstant {

        @Test
        @DisplayName("should be 4429 (mirroring HTTP 429)")
        void shouldBe4429() {
            assertEquals((short) 4429, WebSocketRateLimitService.WS_CLOSE_CODE_RATE_LIMITED);
        }
    }
}
