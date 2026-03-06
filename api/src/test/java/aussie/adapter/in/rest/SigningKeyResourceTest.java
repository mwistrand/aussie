package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.service.auth.KeyRotationService;
import aussie.core.service.auth.SigningKeyRegistry;

@DisplayName("SigningKeyResource")
@ExtendWith(MockitoExtension.class)
class SigningKeyResourceTest {

    @Mock
    private SigningKeyRegistry keyRegistry;

    @Mock
    private KeyRotationService keyRotationService;

    @Mock
    private KeyRotationConfig keyRotationConfig;

    private SigningKeyResource resource;

    @BeforeEach
    void setUp() {
        resource = new SigningKeyResource(keyRegistry, keyRotationService, keyRotationConfig);
    }

    private SigningKeyRecord createKeyRecord(String keyId, KeyStatus status) {
        var publicKey = mock(RSAPublicKey.class);
        var now = Instant.now();
        return new SigningKeyRecord(
                keyId,
                null,
                publicKey,
                status,
                now,
                status == KeyStatus.ACTIVE || status == KeyStatus.DEPRECATED || status == KeyStatus.RETIRED
                        ? now
                        : null,
                status == KeyStatus.DEPRECATED || status == KeyStatus.RETIRED ? now : null,
                status == KeyStatus.RETIRED ? now : null);
    }

    @Nested
    @DisplayName("ensureKeyRotationEnabled")
    class EnsureKeyRotationEnabled {

        @Test
        @DisplayName("throws HttpProblem when key rotation is disabled")
        void throwsWhenDisabled() {
            when(keyRotationConfig.enabled()).thenReturn(false);

            var ex = assertThrows(HttpProblem.class, () -> resource.listKeys());
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }

    @Nested
    @DisplayName("listKeys")
    class ListKeys {

        @Test
        @DisplayName("returns list of key summaries")
        void returnsKeySummaries() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            var key1 = createKeyRecord("key-1", KeyStatus.ACTIVE);
            var key2 = createKeyRecord("key-2", KeyStatus.DEPRECATED);
            when(keyRotationService.listAllKeys()).thenReturn(Uni.createFrom().item(List.of(key1, key2)));

            var result = resource.listKeys().await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
            assertEquals("key-1", result.get(0).keyId());
            assertEquals(KeyStatus.ACTIVE, result.get(0).status());
            assertEquals("key-2", result.get(1).keyId());
            assertEquals(KeyStatus.DEPRECATED, result.get(1).status());
        }

        @Test
        @DisplayName("returns empty list when no keys exist")
        void returnsEmptyList() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.listAllKeys()).thenReturn(Uni.createFrom().item(List.of()));

            var result = resource.listKeys().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getKey")
    class GetKey {

        @Test
        @DisplayName("returns key detail without public key by default")
        void returnsKeyDetailWithoutPublicKey() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            var key = createKeyRecord("key-1", KeyStatus.ACTIVE);
            when(keyRotationService.getKey("key-1")).thenReturn(Uni.createFrom().item(key));

            var result = resource.getKey("key-1", false).await().atMost(Duration.ofSeconds(5));

            assertEquals("key-1", result.keyId());
            assertEquals(KeyStatus.ACTIVE, result.status());
            assertNull(result.publicKey());
        }

        @Test
        @DisplayName("returns key detail with public key when includePublicKey is true")
        void returnsKeyDetailWithPublicKey() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            var publicKey = mock(RSAPublicKey.class);
            when(publicKey.getAlgorithm()).thenReturn("RSA");
            when(publicKey.getFormat()).thenReturn("X.509");
            when(publicKey.getModulus()).thenReturn(BigInteger.valueOf(2).pow(2048));

            var key = new SigningKeyRecord(
                    "key-1", null, publicKey, KeyStatus.ACTIVE, Instant.now(), Instant.now(), null, null);
            when(keyRotationService.getKey("key-1")).thenReturn(Uni.createFrom().item(key));

            var result = resource.getKey("key-1", true).await().atMost(Duration.ofSeconds(5));

            assertNotNull(result.publicKey());
            assertEquals("RSA", result.publicKey().get("algorithm"));
            assertEquals("X.509", result.publicKey().get("format"));
        }

        @Test
        @DisplayName("throws HttpProblem when key not found")
        void throwsWhenKeyNotFound() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.getKey("nonexistent"))
                    .thenReturn(Uni.createFrom()
                            .failure(new KeyRotationService.KeyNotFoundException("Key not found: nonexistent")));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.getKey("nonexistent", false).await().atMost(Duration.ofSeconds(5)));
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }

    @Nested
    @DisplayName("rotateKeys")
    class RotateKeys {

        @Test
        @DisplayName("rotates keys with provided reason")
        void rotatesWithReason() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            var newKey = createKeyRecord("new-key", KeyStatus.ACTIVE);
            when(keyRotationService.triggerRotation("security update"))
                    .thenReturn(Uni.createFrom().item(newKey));

            var request = new SigningKeyResource.RotateKeyRequest("security update");
            var response = resource.rotateKeys(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
            verify(keyRotationService).triggerRotation("security update");
        }

        @Test
        @DisplayName("uses default reason when request reason is null")
        void usesDefaultReasonWhenNull() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            var newKey = createKeyRecord("new-key", KeyStatus.ACTIVE);
            when(keyRotationService.triggerRotation("Manual rotation via admin API"))
                    .thenReturn(Uni.createFrom().item(newKey));

            var request = new SigningKeyResource.RotateKeyRequest(null);
            resource.rotateKeys(request).await().atMost(Duration.ofSeconds(5));

            verify(keyRotationService).triggerRotation("Manual rotation via admin API");
        }

        @Test
        @DisplayName("uses default reason when request is null")
        void usesDefaultReasonWhenRequestNull() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            var newKey = createKeyRecord("new-key", KeyStatus.ACTIVE);
            when(keyRotationService.triggerRotation("Manual rotation via admin API"))
                    .thenReturn(Uni.createFrom().item(newKey));

            resource.rotateKeys(null).await().atMost(Duration.ofSeconds(5));

            verify(keyRotationService).triggerRotation("Manual rotation via admin API");
        }
    }

    @Nested
    @DisplayName("deprecateKey")
    class DeprecateKey {

        @Test
        @DisplayName("deprecates a key and returns 204")
        void deprecatesKeyReturns204() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.forceDeprecate("key-1"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.deprecateKey("key-1").await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(keyRotationService).forceDeprecate("key-1");
        }

        @Test
        @DisplayName("throws HttpProblem when key not found")
        void throwsWhenKeyNotFound() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.forceDeprecate("nonexistent"))
                    .thenReturn(Uni.createFrom()
                            .failure(new KeyRotationService.KeyNotFoundException("Key not found: nonexistent")));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.deprecateKey("nonexistent").await().atMost(Duration.ofSeconds(5)));
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }

    @Nested
    @DisplayName("retireKey")
    class RetireKey {

        @Test
        @DisplayName("retires a key when force=true and returns 204")
        void retiresKeyWhenForceTrue() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.forceRetire("key-1"))
                    .thenReturn(Uni.createFrom().voidItem());

            var response = resource.retireKey("key-1", true).await().atMost(Duration.ofSeconds(5));

            assertEquals(204, response.getStatus());
            verify(keyRotationService).forceRetire("key-1");
        }

        @Test
        @DisplayName("throws HttpProblem when force=false")
        void throwsWhenForceIsFalse() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            var ex = assertThrows(HttpProblem.class, () -> resource.retireKey("key-1", false));
            assertEquals(
                    Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatus().getStatusCode());
        }

        @Test
        @DisplayName("throws HttpProblem when key not found")
        void throwsWhenKeyNotFound() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRotationService.forceRetire("nonexistent"))
                    .thenReturn(Uni.createFrom()
                            .failure(new KeyRotationService.KeyNotFoundException("Key not found: nonexistent")));

            var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.retireKey("nonexistent", true).await().atMost(Duration.ofSeconds(5)));
            assertEquals(
                    Response.Status.NOT_FOUND.getStatusCode(), ex.getStatus().getStatusCode());
        }
    }

    @Nested
    @DisplayName("getHealth")
    class GetHealth {

        @Test
        @DisplayName("returns disabled status when key rotation is disabled")
        void returnsDisabledWhenRotationDisabled() {
            when(keyRotationConfig.enabled()).thenReturn(false);

            var result = resource.getHealth().await().atMost(Duration.ofSeconds(5));

            assertFalse(result.enabled());
            assertEquals("disabled", result.status());
            assertNull(result.activeKeyId());
            assertEquals(0, result.verificationKeyCount());
        }

        @Test
        @DisplayName("returns healthy status with active key info")
        void returnsHealthyWithActiveKey() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            var activeKey = createKeyRecord("active-key", KeyStatus.ACTIVE);
            var deprecatedKey = createKeyRecord("deprecated-key", KeyStatus.DEPRECATED);
            var retiredKey = createKeyRecord("retired-key", KeyStatus.RETIRED);
            when(keyRotationService.listAllKeys())
                    .thenReturn(Uni.createFrom().item(List.of(activeKey, deprecatedKey, retiredKey)));

            var lastRefresh = Instant.now();
            when(keyRegistry.getLastRefreshTime()).thenReturn(Optional.of(lastRefresh));
            when(keyRegistry.isReady()).thenReturn(true);

            var result = resource.getHealth().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.enabled());
            assertEquals("healthy", result.status());
            assertEquals("active-key", result.activeKeyId());
            assertEquals(2, result.verificationKeyCount());
            assertEquals(lastRefresh, result.lastCacheRefresh());
        }

        @Test
        @DisplayName("returns initializing status when registry is not ready")
        void returnsInitializingWhenNotReady() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            when(keyRotationService.listAllKeys()).thenReturn(Uni.createFrom().item(List.of()));
            when(keyRegistry.getLastRefreshTime()).thenReturn(Optional.empty());
            when(keyRegistry.isReady()).thenReturn(false);

            var result = resource.getHealth().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.enabled());
            assertEquals("initializing", result.status());
            assertNull(result.activeKeyId());
            assertEquals(0, result.verificationKeyCount());
            assertNull(result.lastCacheRefresh());
        }
    }
}
