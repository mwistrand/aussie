package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.spi.SigningKeyRepository;

@DisplayName("KeyRotationService")
class KeyRotationServiceTest {

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    private SigningKeyRegistry registry;
    private SigningKeyRepository repository;
    private KeyRotationConfig config;
    private KeyRotationService service;

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
        registry = mock(SigningKeyRegistry.class);
        repository = mock(SigningKeyRepository.class);
        config = mock(KeyRotationConfig.class);

        when(config.enabled()).thenReturn(true);
        when(config.gracePeriod()).thenReturn(Duration.ofHours(24));
        when(config.deprecationPeriod()).thenReturn(Duration.ofDays(7));
        when(config.retentionPeriod()).thenReturn(Duration.ofDays(30));

        service = new KeyRotationService(registry, repository, config);
    }

    @Nested
    @DisplayName("rotateKeys()")
    class RotateKeys {

        @Test
        @DisplayName("should do nothing when disabled")
        void shouldDoNothingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new KeyRotationService(registry, repository, config);

            service.rotateKeys().await().atMost(Duration.ofSeconds(1));

            verify(registry, never()).generateAndRegisterKey();
        }

        @Test
        @DisplayName("should generate new key")
        void shouldGenerateNewKey() {
            final var newKey = SigningKeyRecord.pending("k-new", privateKey, publicKey);
            when(registry.generateAndRegisterKey()).thenReturn(Uni.createFrom().item(newKey));

            service.rotateKeys().await().atMost(Duration.ofSeconds(1));

            verify(registry).generateAndRegisterKey();
        }

        @Test
        @DisplayName("should activate immediately when grace period is zero")
        void shouldActivateImmediatelyWhenGracePeriodIsZero() {
            when(config.gracePeriod()).thenReturn(Duration.ZERO);

            final var newKey = SigningKeyRecord.pending("k-new", privateKey, publicKey);
            when(registry.generateAndRegisterKey()).thenReturn(Uni.createFrom().item(newKey));
            when(registry.activateKey("k-new")).thenReturn(Uni.createFrom().voidItem());

            service.rotateKeys().await().atMost(Duration.ofSeconds(1));

            verify(registry).activateKey("k-new");
        }
    }

    @Nested
    @DisplayName("triggerRotation()")
    class TriggerRotation {

        @Test
        @DisplayName("should throw when disabled")
        void shouldThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new KeyRotationService(registry, repository, config);

            assertThrows(
                    IllegalStateException.class,
                    () -> service.triggerRotation("test").await().atMost(Duration.ofSeconds(1)));
        }

        @Test
        @DisplayName("should generate, register and activate new key")
        void shouldGenerateRegisterAndActivateNewKey() {
            final var newKey = SigningKeyRecord.pending("k-new", privateKey, publicKey);
            when(registry.generateAndRegisterKey()).thenReturn(Uni.createFrom().item(newKey));
            when(registry.activateKey("k-new")).thenReturn(Uni.createFrom().voidItem());
            when(repository.findById("k-new")).thenReturn(Uni.createFrom().item(Optional.of(newKey.activate(null))));

            final var result =
                    service.triggerRotation("emergency rotation").await().atMost(Duration.ofSeconds(1));

            assertNotNull(result);
            verify(registry).generateAndRegisterKey();
            verify(registry).activateKey("k-new");
        }
    }

    @Nested
    @DisplayName("cleanupRetiredKeys()")
    class CleanupRetiredKeys {

        @Test
        @DisplayName("should do nothing when disabled")
        void shouldDoNothingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new KeyRotationService(registry, repository, config);

            service.cleanupRetiredKeys().await().atMost(Duration.ofSeconds(1));

            verify(repository, never()).findByStatus(any());
        }

        @Test
        @DisplayName("should delete retired keys past retention period")
        void shouldDeleteRetiredKeysPastRetentionPeriod() {
            // Key retired 31 days ago (past 30-day retention)
            final var oldRetiredKey = new SigningKeyRecord(
                    "k-old",
                    privateKey,
                    publicKey,
                    KeyStatus.RETIRED,
                    Instant.now().minus(Duration.ofDays(90)),
                    Instant.now().minus(Duration.ofDays(60)),
                    Instant.now().minus(Duration.ofDays(40)),
                    Instant.now().minus(Duration.ofDays(31)));

            when(repository.findByStatus(KeyStatus.RETIRED))
                    .thenReturn(Uni.createFrom().item(List.of(oldRetiredKey)));
            when(repository.delete("k-old")).thenReturn(Uni.createFrom().voidItem());

            service.cleanupRetiredKeys().await().atMost(Duration.ofSeconds(1));

            verify(repository).delete("k-old");
        }

        @Test
        @DisplayName("should not delete recently retired keys")
        void shouldNotDeleteRecentlyRetiredKeys() {
            // Key retired 5 days ago (within 30-day retention)
            final var recentRetiredKey = new SigningKeyRecord(
                    "k-recent",
                    privateKey,
                    publicKey,
                    KeyStatus.RETIRED,
                    Instant.now().minus(Duration.ofDays(30)),
                    Instant.now().minus(Duration.ofDays(20)),
                    Instant.now().minus(Duration.ofDays(10)),
                    Instant.now().minus(Duration.ofDays(5)));

            when(repository.findByStatus(KeyStatus.RETIRED))
                    .thenReturn(Uni.createFrom().item(List.of(recentRetiredKey)));

            service.cleanupRetiredKeys().await().atMost(Duration.ofSeconds(1));

            verify(repository, never()).delete(anyString());
        }
    }

    @Nested
    @DisplayName("listAllKeys()")
    class ListAllKeys {

        @Test
        @DisplayName("should return all keys from repository")
        void shouldReturnAllKeysFromRepository() {
            final var keys = List.of(
                    SigningKeyRecord.active("k-active", privateKey, publicKey),
                    SigningKeyRecord.active("k-deprecated", privateKey, publicKey)
                            .deprecate(Instant.now()));
            when(repository.findAll()).thenReturn(Uni.createFrom().item(keys));

            final var result = service.listAllKeys().await().atMost(Duration.ofSeconds(1));

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("getKey()")
    class GetKey {

        @Test
        @DisplayName("should return key when exists")
        void shouldReturnKeyWhenExists() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);
            when(repository.findById("k-test")).thenReturn(Uni.createFrom().item(Optional.of(key)));

            final var result = service.getKey("k-test").await().atMost(Duration.ofSeconds(1));

            assertEquals("k-test", result.keyId());
        }

        @Test
        @DisplayName("should throw when key not found")
        void shouldThrowWhenKeyNotFound() {
            when(repository.findById("k-missing")).thenReturn(Uni.createFrom().item(Optional.empty()));

            assertThrows(
                    KeyRotationService.KeyNotFoundException.class,
                    () -> service.getKey("k-missing").await().atMost(Duration.ofSeconds(1)));
        }
    }

    @Nested
    @DisplayName("forceDeprecate()")
    class ForceDeprecate {

        @Test
        @DisplayName("should deprecate key via registry")
        void shouldDeprecateKeyViaRegistry() {
            when(registry.deprecateKey("k-test")).thenReturn(Uni.createFrom().voidItem());

            service.forceDeprecate("k-test").await().atMost(Duration.ofSeconds(1));

            verify(registry).deprecateKey("k-test");
        }
    }

    @Nested
    @DisplayName("forceRetire()")
    class ForceRetire {

        @Test
        @DisplayName("should retire key via registry")
        void shouldRetireKeyViaRegistry() {
            when(registry.retireKey("k-test")).thenReturn(Uni.createFrom().voidItem());

            service.forceRetire("k-test").await().atMost(Duration.ofSeconds(1));

            verify(registry).retireKey("k-test");
        }
    }
}
