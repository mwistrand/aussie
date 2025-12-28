package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

@DisplayName("SigningKeyRegistry")
class SigningKeyRegistryTest {

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    private SigningKeyRepository repository;
    private KeyRotationConfig config;
    private SigningKeyRegistry registry;

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
        repository = mock(SigningKeyRepository.class);
        config = mock(KeyRotationConfig.class);

        // Default config - key rotation disabled
        when(config.enabled()).thenReturn(false);
        when(config.keySize()).thenReturn(2048);
        when(config.cacheRefreshInterval()).thenReturn(Duration.ofMinutes(5));

        registry = new SigningKeyRegistry(repository, config);
    }

    @Nested
    @DisplayName("when key rotation is disabled")
    class WhenDisabled {

        @Test
        @DisplayName("getCurrentSigningKey() should throw")
        void getCurrentSigningKeyShouldThrow() {
            assertThrows(IllegalStateException.class, () -> registry.getCurrentSigningKey());
        }

        @Test
        @DisplayName("getVerificationKey() should return empty")
        void getVerificationKeyShouldReturnEmpty() {
            final var result = registry.getVerificationKey("any-key");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("isReady() should return false")
        void isReadyShouldReturnFalse() {
            assertFalse(registry.isReady());
        }
    }

    @Nested
    @DisplayName("when key rotation is enabled")
    class WhenEnabled {

        @BeforeEach
        void enableKeyRotation() {
            when(config.enabled()).thenReturn(true);
            registry = new SigningKeyRegistry(repository, config);
        }

        @Test
        @DisplayName("getCurrentSigningKey() should return cached active key")
        void getCurrentSigningKeyShouldReturnCachedActiveKey() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);

            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.of(activeKey)));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of(activeKey)));

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            final var result = registry.getCurrentSigningKey();

            assertEquals("k-active", result.keyId());
            assertEquals(KeyStatus.ACTIVE, result.status());
        }

        @Test
        @DisplayName("getCurrentSigningKey() should throw when no active key")
        void getCurrentSigningKeyShouldThrowWhenNoActiveKey() {
            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.empty()));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of()));

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            assertThrows(IllegalStateException.class, () -> registry.getCurrentSigningKey());
        }

        @Test
        @DisplayName("getVerificationKey() should return key by ID")
        void getVerificationKeyShouldReturnKeyById() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);
            final var deprecatedKey = SigningKeyRecord.active("k-deprecated", privateKey, publicKey)
                    .deprecate(Instant.now());

            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.of(activeKey)));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of(activeKey, deprecatedKey)));

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            final var active = registry.getVerificationKey("k-active");
            final var deprecated = registry.getVerificationKey("k-deprecated");
            final var missing = registry.getVerificationKey("k-missing");

            assertTrue(active.isPresent());
            assertEquals("k-active", active.get().keyId());

            assertTrue(deprecated.isPresent());
            assertEquals("k-deprecated", deprecated.get().keyId());

            assertTrue(missing.isEmpty());
        }

        @Test
        @DisplayName("getVerificationKeys() should return all verification keys")
        void getVerificationKeysShouldReturnAllVerificationKeys() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);
            final var deprecatedKey = SigningKeyRecord.active("k-deprecated", privateKey, publicKey)
                    .deprecate(Instant.now());

            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.of(activeKey)));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of(activeKey, deprecatedKey)));

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            final var result = registry.getVerificationKeys();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("isReady() should return true after refresh")
        void isReadyShouldReturnTrueAfterRefresh() {
            final var activeKey = SigningKeyRecord.active("k-active", privateKey, publicKey);

            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.of(activeKey)));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of(activeKey)));

            assertFalse(registry.isReady());

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            assertTrue(registry.isReady());
        }

        @Test
        @DisplayName("getLastRefreshTime() should return timestamp after refresh")
        void getLastRefreshTimeShouldReturnTimestampAfterRefresh() {
            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.empty()));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of()));

            assertTrue(registry.getLastRefreshTime().isEmpty());

            registry.refreshCache().await().atMost(Duration.ofSeconds(1));

            assertTrue(registry.getLastRefreshTime().isPresent());
        }
    }

    @Nested
    @DisplayName("key registration")
    class KeyRegistration {

        @BeforeEach
        void enableKeyRotation() {
            when(config.enabled()).thenReturn(true);
            when(repository.store(any())).thenReturn(Uni.createFrom().voidItem());
            registry = new SigningKeyRegistry(repository, config);
        }

        @Test
        @DisplayName("registerKey() should store key with PENDING status")
        void registerKeyShouldStoreKeyWithPendingStatus() {
            final var result =
                    registry.registerKey(privateKey, publicKey).await().atMost(Duration.ofSeconds(1));

            assertNotNull(result.keyId());
            assertEquals(KeyStatus.PENDING, result.status());
            verify(repository).store(any(SigningKeyRecord.class));
        }

        @Test
        @DisplayName("generateAndRegisterKey() should generate and store key")
        void generateAndRegisterKeyShouldGenerateAndStoreKey() {
            final var result = registry.generateAndRegisterKey().await().atMost(Duration.ofSeconds(1));

            assertNotNull(result.keyId());
            assertNotNull(result.privateKey());
            assertNotNull(result.publicKey());
            assertEquals(KeyStatus.PENDING, result.status());
            verify(repository).store(any(SigningKeyRecord.class));
        }
    }

    @Nested
    @DisplayName("key lifecycle")
    class KeyLifecycle {

        @BeforeEach
        void enableKeyRotation() {
            when(config.enabled()).thenReturn(true);
            registry = new SigningKeyRegistry(repository, config);
        }

        @Test
        @DisplayName("activateKey() should deprecate current and activate new")
        void activateKeyShouldDeprecateCurrentAndActivateNew() {
            final var currentActive = SigningKeyRecord.active("k-old", privateKey, publicKey);

            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.of(currentActive)));
            when(repository.updateStatus(anyString(), any(KeyStatus.class), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of()));

            registry.activateKey("k-new").await().atMost(Duration.ofSeconds(1));

            verify(repository).updateStatus(eq("k-old"), eq(KeyStatus.DEPRECATED), any());
            verify(repository).updateStatus(eq("k-new"), eq(KeyStatus.ACTIVE), any());
        }

        @Test
        @DisplayName("deprecateKey() should update status to DEPRECATED")
        void deprecateKeyShouldUpdateStatus() {
            when(repository.updateStatus(anyString(), any(KeyStatus.class), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.empty()));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of()));

            registry.deprecateKey("k-test").await().atMost(Duration.ofSeconds(1));

            verify(repository).updateStatus(eq("k-test"), eq(KeyStatus.DEPRECATED), any());
        }

        @Test
        @DisplayName("retireKey() should update status to RETIRED")
        void retireKeyShouldUpdateStatus() {
            when(repository.updateStatus(anyString(), any(KeyStatus.class), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(repository.findActive()).thenReturn(Uni.createFrom().item(Optional.empty()));
            when(repository.findAllForVerification())
                    .thenReturn(Uni.createFrom().item(List.of()));

            registry.retireKey("k-test").await().atMost(Duration.ofSeconds(1));

            verify(repository).updateStatus(eq("k-test"), eq(KeyStatus.RETIRED), any());
        }
    }
}
