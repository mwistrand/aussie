package aussie.core.service.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("WebSocketRateLimitService")
class WebSocketRateLimitServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private RateLimiter rateLimiter;
    private RateLimitingConfig config;
    private RateLimitResolver rateLimitResolver;
    private ServiceRegistry serviceRegistry;
    private RateLimitingConfig.WebSocketRateLimitConfig wsConfig;
    private RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig connConfig;
    private RateLimitingConfig.WebSocketRateLimitConfig.MessageConfig msgConfig;

    private WebSocketRateLimitService service;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        config = mock(RateLimitingConfig.class);
        rateLimitResolver = mock(RateLimitResolver.class);
        serviceRegistry = mock(ServiceRegistry.class);

        wsConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.class);
        connConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.ConnectionConfig.class);
        msgConfig = mock(RateLimitingConfig.WebSocketRateLimitConfig.MessageConfig.class);

        when(config.websocket()).thenReturn(wsConfig);
        when(wsConfig.connection()).thenReturn(connConfig);
        when(wsConfig.message()).thenReturn(msgConfig);
        when(config.enabled()).thenReturn(true);
        when(rateLimiter.isEnabled()).thenReturn(true);
        when(connConfig.enabled()).thenReturn(true);
        when(msgConfig.enabled()).thenReturn(true);

        service = new WebSocketRateLimitService(rateLimiter, config, rateLimitResolver, serviceRegistry);
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when config and rateLimiter are both enabled")
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
        @DisplayName("should return false when rateLimiter is disabled")
        void shouldReturnFalseWhenRateLimiterDisabled() {
            when(rateLimiter.isEnabled()).thenReturn(false);
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("isConnectionRateLimitEnabled()")
    class IsConnectionRateLimitEnabledTests {

        @Test
        @DisplayName("should return true when all enabled")
        void shouldReturnTrueWhenAllEnabled() {
            assertTrue(service.isConnectionRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when config disabled")
        void shouldReturnFalseWhenConfigDisabled() {
            when(config.enabled()).thenReturn(false);
            assertFalse(service.isConnectionRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when rateLimiter disabled")
        void shouldReturnFalseWhenRateLimiterDisabled() {
            when(rateLimiter.isEnabled()).thenReturn(false);
            assertFalse(service.isConnectionRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when connection config disabled")
        void shouldReturnFalseWhenConnectionDisabled() {
            when(connConfig.enabled()).thenReturn(false);
            assertFalse(service.isConnectionRateLimitEnabled());
        }
    }

    @Nested
    @DisplayName("isMessageRateLimitEnabled()")
    class IsMessageRateLimitEnabledTests {

        @Test
        @DisplayName("should return true when all enabled")
        void shouldReturnTrueWhenAllEnabled() {
            assertTrue(service.isMessageRateLimitEnabled());
        }

        @Test
        @DisplayName("should return false when message config disabled")
        void shouldReturnFalseWhenMessageDisabled() {
            when(msgConfig.enabled()).thenReturn(false);
            assertFalse(service.isMessageRateLimitEnabled());
        }
    }

    @Nested
    @DisplayName("checkMessageLimit()")
    class CheckMessageLimitTests {

        @Test
        @DisplayName("should return allow when message rate limiting disabled")
        void shouldReturnAllowWhenDisabled() {
            when(msgConfig.enabled()).thenReturn(false);

            var result = service.checkMessageLimit("svc", "client-1", "conn-1")
                    .await()
                    .atMost(TIMEOUT);

            assertTrue(result.allowed());
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }

        @Test
        @DisplayName("should check rate limit when enabled")
        void shouldCheckRateLimitWhenEnabled() {
            var limit = new EffectiveRateLimit(100, 1, 50);
            when(serviceRegistry.getServiceForRateLimiting("svc"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(rateLimitResolver.resolveWebSocketMessageLimit(any())).thenReturn(limit);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            var result = service.checkMessageLimit("svc", "client-1", "conn-1")
                    .await()
                    .atMost(TIMEOUT);

            assertTrue(result.allowed());
            verify(rateLimiter).checkAndConsume(any(), any());
        }
    }

    @Nested
    @DisplayName("cleanupConnection()")
    class CleanupConnectionTests {

        @Test
        @DisplayName("should remove keys matching connection pattern")
        void shouldRemoveKeysMatchingPattern() {
            when(rateLimiter.removeKeysMatching(any()))
                    .thenReturn(Uni.createFrom().voidItem());

            service.cleanupConnection("svc", "client-1", "conn-1").await().atMost(TIMEOUT);

            verify(rateLimiter).removeKeysMatching("ws_message:client-1:svc:conn-1");
        }
    }

    @Test
    @DisplayName("WS_CLOSE_CODE_RATE_LIMITED should be 4429")
    void closeCodeShouldBe4429() {
        assertEquals(4429, WebSocketRateLimitService.WS_CLOSE_CODE_RATE_LIMITED);
    }
}
