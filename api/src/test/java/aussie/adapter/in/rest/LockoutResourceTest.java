package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
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
import aussie.core.service.auth.AuthRateLimitService;
import aussie.spi.FailedAttemptRepository.LockoutInfo;

@DisplayName("LockoutResource")
@ExtendWith(MockitoExtension.class)
class LockoutResourceTest {

    @Mock
    private AuthRateLimitService rateLimitService;

    @Mock
    private AuthRateLimitConfig config;

    private LockoutResource resource;

    @BeforeEach
    void setUp() {
        resource = new LockoutResource(rateLimitService, config);
    }

    private LockoutInfo createLockoutInfo(String key) {
        return new LockoutInfo(
                key,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:15:00Z"),
                "max_failed_attempts",
                5,
                1);
    }

    @Nested
    @DisplayName("Feature disabled")
    class FeatureDisabled {

        @Test
        @DisplayName("listLockouts throws HttpProblem when disabled")
        void listLockoutsThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.listLockouts(null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("getIpLockoutStatus throws HttpProblem when disabled")
        void getIpLockoutStatusThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.getIpLockoutStatus("1.2.3.4"));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("getUserLockoutStatus throws HttpProblem when disabled")
        void getUserLockoutStatusThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.getUserLockoutStatus("user@example.com"));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("getApiKeyLockoutStatus throws HttpProblem when disabled")
        void getApiKeyLockoutStatusThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.getApiKeyLockoutStatus("abc12345"));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("clearIpLockout throws HttpProblem when disabled")
        void clearIpLockoutThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.clearIpLockout("1.2.3.4", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("clearUserLockout throws HttpProblem when disabled")
        void clearUserLockoutThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.clearUserLockout("user@example.com", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("clearApiKeyLockout throws HttpProblem when disabled")
        void clearApiKeyLockoutThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.clearApiKeyLockout("abc12345", null));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("clearAllLockouts throws HttpProblem when disabled")
        void clearAllLockoutsThrowsWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.clearAllLockouts(new LockoutResource.ClearAllLockoutsRequest(true, "test")));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("listLockouts")
    class ListLockouts {

        @Test
        @DisplayName("returns lockout list with default limit of 100")
        @SuppressWarnings("unchecked")
        void returnsLockoutListWithDefaultLimit() {
            when(config.enabled()).thenReturn(true);
            var lockout = createLockoutInfo("ip:1.2.3.4");
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().item(lockout));

            var response = resource.listLockouts(null).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(1, entity.get("count"));
            assertEquals(100, entity.get("limit"));
        }

        @Test
        @DisplayName("respects provided limit parameter")
        @SuppressWarnings("unchecked")
        void respectsProvidedLimit() {
            when(config.enabled()).thenReturn(true);
            var lockout = createLockoutInfo("ip:1.2.3.4");
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().item(lockout));

            var response = resource.listLockouts(10).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(10, entity.get("limit"));
        }

        @Test
        @DisplayName("negative limit defaults to 100")
        @SuppressWarnings("unchecked")
        void negativeLimitDefaultsTo100() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().empty());

            var response = resource.listLockouts(-1).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }

        @Test
        @DisplayName("zero limit defaults to 100")
        @SuppressWarnings("unchecked")
        void zeroLimitDefaultsTo100() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().empty());

            var response = resource.listLockouts(0).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            assertEquals(100, entity.get("limit"));
        }
    }

    @Nested
    @DisplayName("Lockout status")
    class LockoutStatus {

        @Test
        @DisplayName("getIpLockoutStatus returns status with lockout info when locked")
        @SuppressWarnings("unchecked")
        void ipStatusWithLockoutInfo() {
            when(config.enabled()).thenReturn(true);
            when(config.maxFailedAttempts()).thenReturn(5);
            var lockoutInfo = createLockoutInfo("ip:1.2.3.4");

            when(rateLimitService.isLockedOut("ip:1.2.3.4"))
                    .thenReturn(Uni.createFrom().item(true));
            when(rateLimitService.getFailedAttemptCount("ip:1.2.3.4"))
                    .thenReturn(Uni.createFrom().item(5L));
            when(rateLimitService.getLockoutInfo("ip:1.2.3.4"))
                    .thenReturn(Uni.createFrom().item(lockoutInfo));

            var response = resource.getIpLockoutStatus("1.2.3.4").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("ip", entity.get("type"));
            assertEquals("1.2.3.4", entity.get("value"));
            assertEquals("ip:1.2.3.4", entity.get("key"));
            assertEquals(true, entity.get("lockedOut"));
            assertEquals(5L, entity.get("failedAttempts"));
            assertEquals(5, entity.get("maxAttempts"));
            assertEquals("2026-01-01T00:00:00Z", entity.get("lockoutStarted"));
            assertEquals("2026-01-01T00:15:00Z", entity.get("lockoutExpires"));
            assertEquals(1, entity.get("lockoutCount"));
        }

        @Test
        @DisplayName("getUserLockoutStatus returns status without lockout info when not locked")
        @SuppressWarnings("unchecked")
        void userStatusWithoutLockoutInfo() {
            when(config.enabled()).thenReturn(true);
            when(config.maxFailedAttempts()).thenReturn(5);

            when(rateLimitService.isLockedOut("user:john@example.com"))
                    .thenReturn(Uni.createFrom().item(false));
            when(rateLimitService.getFailedAttemptCount("user:john@example.com"))
                    .thenReturn(Uni.createFrom().item(2L));
            when(rateLimitService.getLockoutInfo("user:john@example.com"))
                    .thenReturn(Uni.createFrom().nullItem());

            var response =
                    resource.getUserLockoutStatus("john@example.com").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("user", entity.get("type"));
            assertEquals("john@example.com", entity.get("value"));
            assertEquals(false, entity.get("lockedOut"));
            assertEquals(2L, entity.get("failedAttempts"));
            assertFalse(entity.containsKey("lockoutStarted"));
        }

        @Test
        @DisplayName("getApiKeyLockoutStatus uses correct key prefix")
        @SuppressWarnings("unchecked")
        void apiKeyStatusUsesCorrectPrefix() {
            when(config.enabled()).thenReturn(true);
            when(config.maxFailedAttempts()).thenReturn(5);

            when(rateLimitService.isLockedOut("apikey:abc12345"))
                    .thenReturn(Uni.createFrom().item(false));
            when(rateLimitService.getFailedAttemptCount("apikey:abc12345"))
                    .thenReturn(Uni.createFrom().item(0L));
            when(rateLimitService.getLockoutInfo("apikey:abc12345"))
                    .thenReturn(Uni.createFrom().nullItem());

            var response = resource.getApiKeyLockoutStatus("abc12345").await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("apikey", entity.get("type"));
            assertEquals("abc12345", entity.get("value"));
            assertEquals("apikey:abc12345", entity.get("key"));
        }
    }

    @Nested
    @DisplayName("Clear lockouts")
    class ClearLockouts {

        @Test
        @DisplayName("clearIpLockout returns 204")
        void clearIpLockoutReturns204() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.clearIpLockout("1.2.3.4"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.clearIpLockout("1.2.3.4", null).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(rateLimitService).clearIpLockout("1.2.3.4");
        }

        @Test
        @DisplayName("clearIpLockout accepts request with reason")
        void clearIpLockoutWithReason() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.clearIpLockout("1.2.3.4"))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new LockoutResource.ClearLockoutRequest("admin override");
            var response = resource.clearIpLockout("1.2.3.4", request).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
        }

        @Test
        @DisplayName("clearUserLockout returns 204")
        void clearUserLockoutReturns204() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.clearUserLockout("user@example.com"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response =
                    resource.clearUserLockout("user@example.com", null).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(rateLimitService).clearUserLockout("user@example.com");
        }

        @Test
        @DisplayName("clearApiKeyLockout returns 204")
        void clearApiKeyLockoutReturns204() {
            when(config.enabled()).thenReturn(true);
            when(rateLimitService.clearLockout("apikey:abc12345"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.clearApiKeyLockout("abc12345", null).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(rateLimitService).clearLockout("apikey:abc12345");
        }
    }

    @Nested
    @DisplayName("clearAllLockouts")
    class ClearAllLockouts {

        @Test
        @DisplayName("throws HttpProblem when force is false")
        void throwsWhenForceIsFalse() {
            when(config.enabled()).thenReturn(true);

            var request = new LockoutResource.ClearAllLockoutsRequest(false, "test");
            var ex = assertThrows(HttpProblem.class, () -> resource.clearAllLockouts(request));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when request is null")
        void throwsWhenRequestIsNull() {
            when(config.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.clearAllLockouts(null));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("clears all lockouts when force=true")
        @SuppressWarnings("unchecked")
        void clearsAllWhenForceTrue() {
            when(config.enabled()).thenReturn(true);
            var lockout1 = createLockoutInfo("ip:1.2.3.4");
            var lockout2 = createLockoutInfo("user:test@example.com");
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().items(lockout1, lockout2));
            when(rateLimitService.clearLockout(anyString()))
                    .thenReturn(Uni.createFrom().voidItem());

            var request = new LockoutResource.ClearAllLockoutsRequest(true, "emergency");
            var response = resource.clearAllLockouts(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            var entity = (Map<String, Object>) response.getEntity();
            assertEquals("cleared", entity.get("status"));
            assertEquals(2, entity.get("count"));
            verify(rateLimitService).clearLockout("ip:1.2.3.4");
            verify(rateLimitService).clearLockout("user:test@example.com");
        }
    }

    @Nested
    @DisplayName("formatLockoutInfo")
    class FormatLockoutInfo {

        @Test
        @DisplayName("parses key with type prefix correctly")
        @SuppressWarnings("unchecked")
        void parsesKeyWithTypePrefix() {
            when(config.enabled()).thenReturn(true);
            var lockout = new LockoutInfo(
                    "ip:192.168.1.1",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:15:00Z"),
                    "brute_force",
                    10,
                    2);
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().item(lockout));

            var response = resource.listLockouts(10).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            var lockouts = (java.util.List<Map<String, Object>>) entity.get("lockouts");
            var formatted = lockouts.get(0);
            assertEquals("ip:192.168.1.1", formatted.get("key"));
            assertEquals("ip", formatted.get("type"));
            assertEquals("192.168.1.1", formatted.get("value"));
            assertEquals("2026-01-01T00:00:00Z", formatted.get("lockedAt"));
            assertEquals("2026-01-01T00:15:00Z", formatted.get("expiresAt"));
            assertEquals("brute_force", formatted.get("reason"));
            assertEquals(10, formatted.get("failedAttempts"));
            assertEquals(2, formatted.get("lockoutCount"));
        }

        @Test
        @DisplayName("handles null reason with default value")
        @SuppressWarnings("unchecked")
        void handlesNullReason() {
            when(config.enabled()).thenReturn(true);
            var lockout = new LockoutInfo(
                    "user:test",
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:15:00Z"),
                    null,
                    3,
                    1);
            when(rateLimitService.streamAllLockouts())
                    .thenReturn(Multi.createFrom().item(lockout));

            var response = resource.listLockouts(10).await().atMost(Duration.ofSeconds(5));

            var entity = (Map<String, Object>) response.getEntity();
            var lockouts = (java.util.List<Map<String, Object>>) entity.get("lockouts");
            assertEquals("max_failed_attempts", lockouts.get(0).get("reason"));
        }
    }
}
