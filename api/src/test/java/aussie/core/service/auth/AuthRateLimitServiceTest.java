package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.AuthRateLimitConfig;
import aussie.spi.FailedAttemptRepository;

@DisplayName("AuthRateLimitService")
@ExtendWith(MockitoExtension.class)
class AuthRateLimitServiceTest {

    @Mock
    private AuthRateLimitConfig config;

    @Mock
    private FailedAttemptRepository repository;

    private AuthRateLimitService service;

    @BeforeEach
    void setUp() {
        // Setup default config mocks
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.maxFailedAttempts()).thenReturn(5);
        lenient().when(config.lockoutDuration()).thenReturn(Duration.ofMinutes(15));
        lenient().when(config.failedAttemptWindow()).thenReturn(Duration.ofHours(1));
        lenient().when(config.trackByIp()).thenReturn(true);
        lenient().when(config.trackByIdentifier()).thenReturn(true);
        lenient().when(config.progressiveLockoutMultiplier()).thenReturn(1.5);
        lenient().when(config.maxLockoutDuration()).thenReturn(Duration.ofHours(24));

        service = new AuthRateLimitService(config, repository);
    }

    @Nested
    @DisplayName("checkAuthLimit()")
    class CheckAuthLimitTests {

        @Test
        @DisplayName("should allow when disabled")
        void shouldAllowWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new AuthRateLimitService(config, repository);

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isAllowed());
            assertNull(result.key());
        }

        @Test
        @DisplayName("should allow when not locked out")
        void shouldAllowWhenNotLockedOut() {
            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.isLockedOut("user:user@example.com"))
                    .thenReturn(Uni.createFrom().item(false));

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isAllowed());
        }

        @Test
        @DisplayName("should block when IP is locked out")
        void shouldBlockWhenIpLockedOut() {
            final var expiry = Instant.now().plus(Duration.ofMinutes(10));
            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.getLockoutExpiry("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(expiry));

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertFalse(result.allowed());
            assertEquals("ip:192.168.1.1", result.key());
            assertTrue(result.retryAfterSeconds() > 0);
        }

        @Test
        @DisplayName("should block when identifier is locked out")
        void shouldBlockWhenIdentifierLockedOut() {
            final var expiry = Instant.now().plus(Duration.ofMinutes(10));
            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.isLockedOut("user:user@example.com"))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.getLockoutExpiry("user:user@example.com"))
                    .thenReturn(Uni.createFrom().item(expiry));

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertFalse(result.allowed());
            assertEquals("user:user@example.com", result.key());
        }

        @Test
        @DisplayName("should only check IP when identifier tracking is disabled")
        void shouldOnlyCheckIpWhenIdentifierTrackingDisabled() {
            when(config.trackByIdentifier()).thenReturn(false);
            service = new AuthRateLimitService(config, repository);

            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(false));

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isAllowed());
            verify(repository, never()).isLockedOut("user:user@example.com");
        }

        @Test
        @DisplayName("should only check identifier when IP tracking is disabled")
        void shouldOnlyCheckIdentifierWhenIpTrackingDisabled() {
            when(config.trackByIp()).thenReturn(false);
            service = new AuthRateLimitService(config, repository);

            when(repository.isLockedOut("user:user@example.com"))
                    .thenReturn(Uni.createFrom().item(false));

            final var result = service.checkAuthLimit("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isAllowed());
            verify(repository, never()).isLockedOut("ip:192.168.1.1");
        }
    }

    @Nested
    @DisplayName("recordFailedAttempt()")
    class RecordFailedAttemptTests {

        @Test
        @DisplayName("should not record when disabled")
        void shouldNotRecordWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new AuthRateLimitService(config, repository);

            final var result = service.recordFailedAttempt("192.168.1.1", "user@example.com", "invalid_password")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertFalse(result.lockedOut());
            verify(repository, never()).recordFailedAttempt(anyString(), any());
        }

        @Test
        @DisplayName("should record attempt and not lockout when under threshold")
        void shouldRecordAttemptWithoutLockout() {
            when(repository.recordFailedAttempt("ip:192.168.1.1", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(3L));

            when(repository.recordFailedAttempt("user:user@example.com", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(2L));

            final var result = service.recordFailedAttempt("192.168.1.1", "user@example.com", "invalid_password")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertFalse(result.lockedOut());
            assertEquals(3, result.attempts()); // Returns higher count
            assertEquals(2, result.remainingAttempts());
        }

        @Test
        @DisplayName("should trigger lockout when threshold reached")
        void shouldTriggerLockoutWhenThresholdReached() {
            when(repository.recordFailedAttempt("ip:192.168.1.1", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(5L)); // Reaches threshold

            when(repository.getLockoutCount("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(0));

            when(repository.recordLockout("ip:192.168.1.1", Duration.ofMinutes(15), "invalid_password"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.recordFailedAttempt("user:user@example.com", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(2L));

            final var result = service.recordFailedAttempt("192.168.1.1", "user@example.com", "invalid_password")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.lockedOut());
            assertEquals("ip:192.168.1.1", result.key());
            verify(repository).recordLockout(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("should apply progressive lockout duration")
        void shouldApplyProgressiveLockoutDuration() {
            when(repository.recordFailedAttempt("ip:192.168.1.1", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(5L));

            // Second lockout should have 1.5x duration
            when(repository.getLockoutCount("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(1));

            // Expected duration: 15 min * 1.5 = 22.5 min
            when(repository.recordLockout("ip:192.168.1.1", Duration.ofSeconds(1350), "invalid_password"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.recordFailedAttempt("user:user@example.com", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(2L));

            final var result = service.recordFailedAttempt("192.168.1.1", "user@example.com", "invalid_password")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.lockedOut());
            assertTrue(result.lockoutSeconds() >= 1350); // 22.5 minutes
        }

        @Test
        @DisplayName("should cap lockout at max duration")
        void shouldCapLockoutAtMaxDuration() {
            when(config.maxLockoutDuration()).thenReturn(Duration.ofHours(1));
            service = new AuthRateLimitService(config, repository);

            when(repository.recordFailedAttempt("ip:192.168.1.1", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(5L));

            // Many previous lockouts - would exceed 1 hour cap
            when(repository.getLockoutCount("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(10));

            // Should be capped at 1 hour
            when(repository.recordLockout("ip:192.168.1.1", Duration.ofHours(1), "invalid_password"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.recordFailedAttempt("user:user@example.com", Duration.ofHours(1)))
                    .thenReturn(Uni.createFrom().item(2L));

            final var result = service.recordFailedAttempt("192.168.1.1", "user@example.com", "invalid_password")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.lockedOut());
            assertEquals(3600, result.lockoutSeconds()); // 1 hour in seconds
        }
    }

    @Nested
    @DisplayName("clearFailedAttempts()")
    class ClearFailedAttemptsTests {

        @Test
        @DisplayName("should clear attempts for both IP and identifier")
        void shouldClearAttemptsForBothKeys() {
            when(repository.clearFailedAttempts("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.clearFailedAttempts("user:user@example.com"))
                    .thenReturn(Uni.createFrom().voidItem());

            service.clearFailedAttempts("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            verify(repository).clearFailedAttempts("ip:192.168.1.1");
            verify(repository).clearFailedAttempts("user:user@example.com");
        }

        @Test
        @DisplayName("should not clear when disabled")
        void shouldNotClearWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new AuthRateLimitService(config, repository);

            service.clearFailedAttempts("192.168.1.1", "user@example.com")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            verify(repository, never()).clearFailedAttempts(anyString());
        }
    }

    @Nested
    @DisplayName("clearLockout()")
    class ClearLockoutTests {

        @Test
        @DisplayName("should clear lockout and failed attempts for key")
        void shouldClearLockoutAndAttempts() {
            when(repository.clearLockout("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.clearFailedAttempts("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().voidItem());

            service.clearIpLockout("192.168.1.1").await().atMost(Duration.ofSeconds(1));

            verify(repository).clearLockout("ip:192.168.1.1");
            verify(repository).clearFailedAttempts("ip:192.168.1.1");
        }

        @Test
        @DisplayName("should clear user lockout")
        void shouldClearUserLockout() {
            when(repository.clearLockout("user:user@example.com"))
                    .thenReturn(Uni.createFrom().voidItem());

            when(repository.clearFailedAttempts("user:user@example.com"))
                    .thenReturn(Uni.createFrom().voidItem());

            service.clearUserLockout("user@example.com").await().atMost(Duration.ofSeconds(1));

            verify(repository).clearLockout("user:user@example.com");
        }
    }

    @Nested
    @DisplayName("checkApiKeyLimit()")
    class CheckApiKeyLimitTests {

        @Test
        @DisplayName("should use apikey prefix for identifier")
        void shouldUseApiKeyPrefix() {
            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.isLockedOut("apikey:sk_live_"))
                    .thenReturn(Uni.createFrom().item(false));

            final var result =
                    service.checkApiKeyLimit("192.168.1.1", "sk_live_").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isAllowed());
            verify(repository).isLockedOut("apikey:sk_live_");
        }

        @Test
        @DisplayName("should block when API key prefix is locked out")
        void shouldBlockWhenApiKeyLockedOut() {
            final var expiry = Instant.now().plus(Duration.ofMinutes(10));
            when(repository.isLockedOut("ip:192.168.1.1"))
                    .thenReturn(Uni.createFrom().item(false));
            when(repository.isLockedOut("apikey:sk_live_"))
                    .thenReturn(Uni.createFrom().item(true));
            when(repository.getLockoutExpiry("apikey:sk_live_"))
                    .thenReturn(Uni.createFrom().item(expiry));

            final var result =
                    service.checkApiKeyLimit("192.168.1.1", "sk_live_").await().atMost(Duration.ofSeconds(1));

            assertFalse(result.allowed());
            assertEquals("apikey:sk_live_", result.key());
        }
    }

    @Nested
    @DisplayName("getLockoutInfo()")
    class GetLockoutInfoTests {

        @Test
        @DisplayName("should return null when not locked out")
        void shouldReturnNullWhenNotLockedOut() {
            when(repository.streamAllLockouts()).thenReturn(Multi.createFrom().items());

            final var info = service.getLockoutInfo("ip:192.168.1.1").await().atMost(Duration.ofSeconds(1));

            assertNull(info);
        }

        @Test
        @DisplayName("should return lockout info when locked out")
        void shouldReturnLockoutInfoWhenLockedOut() {
            final var lockedAt = Instant.now();
            final var expiry = lockedAt.plus(Duration.ofMinutes(10));
            final var lockoutInfo = new FailedAttemptRepository.LockoutInfo(
                    "ip:192.168.1.1", lockedAt, expiry, "max_failed_attempts", 5, 2);

            when(repository.streamAllLockouts()).thenReturn(Multi.createFrom().item(lockoutInfo));

            final var info = service.getLockoutInfo("ip:192.168.1.1").await().atMost(Duration.ofSeconds(1));

            assertNotNull(info);
            assertEquals("ip:192.168.1.1", info.key());
            assertEquals(5, info.failedAttempts());
            assertEquals(2, info.lockoutCount());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return config enabled state")
        void shouldReturnConfigEnabledState() {
            when(config.enabled()).thenReturn(true);
            assertTrue(service.isEnabled());

            when(config.enabled()).thenReturn(false);
            assertFalse(service.isEnabled());
        }
    }
}
