package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;

@DisplayName("ConfigSigningKeyRepository")
class ConfigSigningKeyRepositoryTest {

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    private RouteAuthConfig routeAuthConfig;
    private KeyRotationConfig keyRotationConfig;
    private ConfigSigningKeyRepository repository;

    @BeforeAll
    static void generateTestKeys() throws Exception {
        final var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        final var keyPair = keyGen.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKey = (RSAPublicKey) keyPair.getPublic();
    }

    @BeforeEach
    void setUp() {
        routeAuthConfig = mock(RouteAuthConfig.class);
        keyRotationConfig = mock(KeyRotationConfig.class);

        final var jwsProps = mock(RouteAuthConfig.JwsProperties.class);
        when(routeAuthConfig.enabled()).thenReturn(false);
        when(routeAuthConfig.jws()).thenReturn(jwsProps);
        when(jwsProps.signingKey()).thenReturn(Optional.empty());
        when(jwsProps.keyId()).thenReturn("v1");

        repository = new ConfigSigningKeyRepository(routeAuthConfig, keyRotationConfig);
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("should load configured key on creation")
        void shouldLoadConfiguredKey() {
            var jwsProps = mock(RouteAuthConfig.JwsProperties.class);
            var config = mock(RouteAuthConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.jws()).thenReturn(jwsProps);
            when(jwsProps.signingKey())
                    .thenReturn(Optional.of(Base64.getEncoder().encodeToString(privateKey.getEncoded())));
            when(jwsProps.keyId()).thenReturn("static-v1");

            var repo = new ConfigSigningKeyRepository(config, keyRotationConfig);
            assertEquals(1, repo.getKeyCount());

            var key = repo.findById("static-v1").await().atMost(Duration.ofSeconds(1));
            assertTrue(key.isPresent());
            assertEquals(KeyStatus.ACTIVE, key.get().status());
        }

        @Test
        @DisplayName("should handle invalid signing key gracefully")
        void shouldHandleInvalidKey() {
            var jwsProps = mock(RouteAuthConfig.JwsProperties.class);
            var config = mock(RouteAuthConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.jws()).thenReturn(jwsProps);
            when(jwsProps.signingKey()).thenReturn(Optional.of("not-a-valid-key"));
            when(jwsProps.keyId()).thenReturn("bad-v1");

            var repo = new ConfigSigningKeyRepository(config, keyRotationConfig);
            assertEquals(0, repo.getKeyCount());
        }
    }

    @Nested
    @DisplayName("store()")
    class Store {

        @Test
        @DisplayName("should store key")
        void shouldStoreKey() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);

            repository.store(key).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals("k-test", result.get().keyId());
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        @DisplayName("should return empty when key not found")
        void shouldReturnEmptyWhenKeyNotFound() {
            final var result = repository.findById("missing").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return key when found")
        void shouldReturnKeyWhenFound() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("k-test", result.get().keyId());
        }
    }

    @Nested
    @DisplayName("findActive()")
    class FindActive {

        @Test
        @DisplayName("should return empty when no active key")
        void shouldReturnEmptyWhenNoActiveKey() {
            final var result = repository.findActive().await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return active key")
        void shouldReturnActiveKey() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);
            final var pendingKey = SigningKeyRecord.pending("k-pending", privateKey, publicKey);

            repository.store(activeKey).await().atMost(Duration.ofSeconds(1));
            repository.store(pendingKey).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findActive().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("k-active", result.get().keyId());
        }
    }

    @Nested
    @DisplayName("findAllForVerification()")
    class FindAllForVerification {

        @Test
        @DisplayName("should return empty when no keys")
        void shouldReturnEmptyWhenNoKeys() {
            final var result = repository.findAllForVerification().await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return only ACTIVE and DEPRECATED keys")
        void shouldReturnOnlyActiveAndDeprecatedKeys() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);
            final var deprecatedKey = SigningKeyRecord.active("k-deprecated", privateKey, publicKey)
                    .deprecate(Instant.now());
            final var pendingKey = SigningKeyRecord.pending("k-pending", privateKey, publicKey);
            final var retiredKey = SigningKeyRecord.active("k-retired", privateKey, publicKey)
                    .deprecate(Instant.now())
                    .retire(Instant.now());

            repository.store(activeKey).await().atMost(Duration.ofSeconds(1));
            repository.store(deprecatedKey).await().atMost(Duration.ofSeconds(1));
            repository.store(pendingKey).await().atMost(Duration.ofSeconds(1));
            repository.store(retiredKey).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findAllForVerification().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
            assertTrue(result.stream()
                    .allMatch(k -> k.status() == KeyStatus.ACTIVE || k.status() == KeyStatus.DEPRECATED));
        }
    }

    @Nested
    @DisplayName("findByStatus()")
    class FindByStatus {

        @Test
        @DisplayName("should return keys with matching status")
        void shouldReturnKeysWithMatchingStatus() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);
            final var pendingKey = SigningKeyRecord.pending("k-pending", privateKey, publicKey);

            repository.store(activeKey).await().atMost(Duration.ofSeconds(1));
            repository.store(pendingKey).await().atMost(Duration.ofSeconds(1));

            final var activeResult =
                    repository.findByStatus(KeyStatus.ACTIVE).await().atMost(Duration.ofSeconds(1));
            final var pendingResult =
                    repository.findByStatus(KeyStatus.PENDING).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, activeResult.size());
            assertEquals("k-active", activeResult.get(0).keyId());

            assertEquals(1, pendingResult.size());
            assertEquals("k-pending", pendingResult.get(0).keyId());
        }
    }

    @Nested
    @DisplayName("updateStatus()")
    class UpdateStatus {

        @Test
        @DisplayName("should update key status to ACTIVE")
        void shouldUpdateKeyStatusToActive() {
            final var key = SigningKeyRecord.pending("k-test", privateKey, publicKey);
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            repository
                    .updateStatus("k-test", KeyStatus.ACTIVE, Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals(KeyStatus.ACTIVE, result.get().status());
        }

        @Test
        @DisplayName("should update key status to DEPRECATED")
        void shouldUpdateKeyStatusToDeprecated() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            repository
                    .updateStatus("k-test", KeyStatus.DEPRECATED, Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals(KeyStatus.DEPRECATED, result.get().status());
        }

        @Test
        @DisplayName("should update key status to RETIRED")
        void shouldUpdateKeyStatusToRetired() {
            final var key =
                    SigningKeyRecord.active("k-test", privateKey, publicKey).deprecate(Instant.now());
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            repository
                    .updateStatus("k-test", KeyStatus.RETIRED, Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals(KeyStatus.RETIRED, result.get().status());
        }

        @Test
        @DisplayName("should handle PENDING to PENDING (no-op)")
        void shouldHandlePendingToPending() {
            final var key = SigningKeyRecord.pending("k-test", privateKey, publicKey);
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            repository
                    .updateStatus("k-test", KeyStatus.PENDING, Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals(KeyStatus.PENDING, result.get().status());
        }

        @Test
        @DisplayName("should throw for unknown key ID")
        void shouldThrowForUnknownKeyId() {
            assertThrows(IllegalArgumentException.class, () -> repository
                    .updateStatus("missing", KeyStatus.ACTIVE, Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        @DisplayName("should delete key")
        void shouldDeleteKey() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);
            repository.store(key).await().atMost(Duration.ofSeconds(1));

            repository.delete("k-test").await().atMost(Duration.ofSeconds(1));

            final var result = repository.findById("k-test").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should not fail when deleting non-existent key")
        void shouldNotFailWhenDeletingNonExistentKey() {
            repository.delete("missing").await().atMost(Duration.ofSeconds(1));
            // No exception expected
        }
    }

    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        @DisplayName("should return all keys")
        void shouldReturnAllKeys() {
            final var key1 = SigningKeyRecord.active("k-1", privateKey, publicKey);
            final var key2 = SigningKeyRecord.pending("k-2", privateKey, publicKey);

            repository.store(key1).await().atMost(Duration.ofSeconds(1));
            repository.store(key2).await().atMost(Duration.ofSeconds(1));

            final var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("getKeyCount()")
    class GetKeyCount {

        @Test
        @DisplayName("should return count of stored keys")
        void shouldReturnKeyCount() {
            assertEquals(0, repository.getKeyCount());

            repository
                    .store(SigningKeyRecord.active("k-1", privateKey, publicKey))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            assertEquals(1, repository.getKeyCount());
        }
    }

    @Nested
    @DisplayName("clear()")
    class Clear {

        @Test
        @DisplayName("should remove all keys")
        void shouldRemoveAllKeys() {
            repository
                    .store(SigningKeyRecord.active("k-1", privateKey, publicKey))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store(SigningKeyRecord.active("k-2", privateKey, publicKey))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            assertEquals(2, repository.getKeyCount());

            repository.clear();
            assertEquals(0, repository.getKeyCount());
        }
    }
}
