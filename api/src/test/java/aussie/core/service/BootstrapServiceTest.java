package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.memory.InMemoryApiKeyRepository;
import aussie.config.ApiKeyConfig;
import aussie.config.BootstrapConfig;
import aussie.core.model.ApiKey;
import aussie.core.model.Permissions;
import aussie.core.port.in.BootstrapManagement.BootstrapException;

@DisplayName("BootstrapService")
class BootstrapServiceTest {

    private BootstrapService bootstrapService;
    private InMemoryApiKeyRepository repository;
    private ApiKeyService apiKeyService;

    // Test key that meets the minimum length requirement (32 chars)
    private static final String VALID_BOOTSTRAP_KEY = "test-bootstrap-key-at-least-32-chars!";
    private static final String SHORT_KEY = "short";

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        ApiKeyConfig apiKeyConfig = () -> Optional.empty();
        apiKeyService = new ApiKeyService(repository, apiKeyConfig);
    }

    private BootstrapService createService(BootstrapConfig config) {
        return new BootstrapService(repository, config, apiKeyService);
    }

    private BootstrapConfig createConfig(boolean enabled, boolean recoveryMode, Optional<String> key) {
        return createConfig(enabled, recoveryMode, key, Duration.ofHours(24));
    }

    private BootstrapConfig createConfig(boolean enabled, boolean recoveryMode, Optional<String> key, Duration ttl) {
        return new BootstrapConfig() {
            @Override
            public boolean enabled() {
                return enabled;
            }

            @Override
            public Optional<String> key() {
                return key;
            }

            @Override
            public Duration ttl() {
                return ttl;
            }

            @Override
            public boolean recoveryMode() {
                return recoveryMode;
            }
        };
    }

    private void createAdminKey() {
        apiKeyService.create("existing-admin", "Existing admin key", Set.of(Permissions.ALL), null, "test");
    }

    private void createReadOnlyKey() {
        apiKeyService.create("read-only", "Read-only key", Set.of(Permissions.ADMIN_READ), null, "test");
    }

    @Nested
    @DisplayName("shouldBootstrap")
    class ShouldBootstrapTests {

        @Test
        @DisplayName("should return false when disabled")
        void shouldReturnFalseWhenDisabled() {
            var config = createConfig(false, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.shouldBootstrap());
        }

        @Test
        @DisplayName("should return true when enabled and no admin keys exist")
        void shouldReturnTrueWhenEnabledAndNoAdminKeys() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertTrue(service.shouldBootstrap());
        }

        @Test
        @DisplayName("should return false when enabled but admin keys exist")
        void shouldReturnFalseWhenAdminKeysExist() {
            createAdminKey();
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.shouldBootstrap());
        }

        @Test
        @DisplayName("should return true in recovery mode even with existing admin keys")
        void shouldReturnTrueInRecoveryModeWithExistingKeys() {
            createAdminKey();
            var config = createConfig(true, true, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertTrue(service.shouldBootstrap());
        }
    }

    @Nested
    @DisplayName("hasAdminKeys")
    class HasAdminKeysTests {

        @Test
        @DisplayName("should return false for empty repository")
        void shouldReturnFalseForEmptyRepository() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.hasAdminKeys());
        }

        @Test
        @DisplayName("should return true for key with wildcard permission")
        void shouldReturnTrueForWildcardPermission() {
            apiKeyService.create("admin", null, Set.of(Permissions.ALL), null, "test");
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertTrue(service.hasAdminKeys());
        }

        @Test
        @DisplayName("should return true for key with admin:write permission")
        void shouldReturnTrueForAdminWritePermission() {
            apiKeyService.create("admin", null, Set.of(Permissions.ADMIN_WRITE), null, "test");
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertTrue(service.hasAdminKeys());
        }

        @Test
        @DisplayName("should return false for key with only admin:read permission")
        void shouldReturnFalseForReadOnlyPermission() {
            createReadOnlyKey();
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.hasAdminKeys());
        }

        @Test
        @DisplayName("should ignore revoked admin keys")
        void shouldIgnoreRevokedAdminKeys() {
            var result = apiKeyService.create("admin", null, Set.of(Permissions.ALL), null, "test");
            apiKeyService.revoke(result.keyId());

            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.hasAdminKeys());
        }

        @Test
        @DisplayName("should ignore expired admin keys")
        void shouldIgnoreExpiredAdminKeys() {
            // Create an already-expired key by manipulating the repository directly
            var expiredKey = ApiKey.builder("expired-id", "hash")
                    .name("expired-admin")
                    .permissions(Set.of(Permissions.ALL))
                    .createdAt(Instant.now().minusSeconds(3600))
                    .expiresAt(Instant.now().minusSeconds(1800)) // Expired 30 minutes ago
                    .revoked(false)
                    .build();
            repository.save(expiredKey).await().indefinitely();

            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            assertFalse(service.hasAdminKeys());
        }
    }

    @Nested
    @DisplayName("bootstrap")
    class BootstrapTests {

        @Test
        @DisplayName("should create key with provided value")
        void shouldCreateKeyWithProvidedValue() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            var result = service.bootstrap();

            assertNotNull(result.keyId());
            assertNotNull(result.expiresAt());
            assertFalse(result.wasRecovery());
        }

        @Test
        @DisplayName("should create key that can be validated")
        void shouldCreateKeyThatCanBeValidated() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            service.bootstrap();

            var validated = apiKeyService.validate(VALID_BOOTSTRAP_KEY);
            assertTrue(validated.isPresent());
            assertEquals("bootstrap-admin", validated.get().name());
            assertTrue(validated.get().permissions().contains(Permissions.ALL));
        }

        @Test
        @DisplayName("should throw when key not provided")
        void shouldThrowWhenKeyNotProvided() {
            var config = createConfig(true, false, Optional.empty());
            var service = createService(config);

            assertThrows(BootstrapException.class, service::bootstrap);
        }

        @Test
        @DisplayName("should throw when key is blank")
        void shouldThrowWhenKeyIsBlank() {
            var config = createConfig(true, false, Optional.of("   "));
            var service = createService(config);

            assertThrows(BootstrapException.class, service::bootstrap);
        }

        @Test
        @DisplayName("should throw when key is too short")
        void shouldThrowWhenKeyTooShort() {
            var config = createConfig(true, false, Optional.of(SHORT_KEY));
            var service = createService(config);

            assertThrows(BootstrapException.class, service::bootstrap);
        }

        @Test
        @DisplayName("should enforce maximum TTL of 24 hours")
        void shouldEnforceMaximumTtl() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY), Duration.ofDays(30));
            var service = createService(config);

            var result = service.bootstrap();

            // Expiration should be within 24 hours (plus a small buffer for test execution)
            assertTrue(result.expiresAt().isBefore(Instant.now().plus(Duration.ofHours(25))));
        }

        @Test
        @DisplayName("should use configured TTL when less than maximum")
        void shouldUseConfiguredTtlWhenLessThanMax() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY), Duration.ofHours(12));
            var service = createService(config);

            var result = service.bootstrap();

            // Expiration should be approximately 12 hours (with buffer)
            assertTrue(result.expiresAt().isAfter(Instant.now().plus(Duration.ofHours(11))));
            assertTrue(result.expiresAt().isBefore(Instant.now().plus(Duration.ofHours(13))));
        }

        @Test
        @DisplayName("should mark result as recovery when in recovery mode with existing keys")
        void shouldMarkResultAsRecoveryWhenInRecoveryMode() {
            createAdminKey();
            var config = createConfig(true, true, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            var result = service.bootstrap();

            assertTrue(result.wasRecovery());
        }

        @Test
        @DisplayName("should mark result as non-recovery when no existing keys")
        void shouldMarkResultAsNonRecoveryWhenNoExistingKeys() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY));
            var service = createService(config);

            var result = service.bootstrap();

            assertFalse(result.wasRecovery());
        }

        @Test
        @DisplayName("should throw when TTL is zero")
        void shouldThrowWhenTtlIsZero() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY), Duration.ZERO);
            var service = createService(config);

            assertThrows(BootstrapException.class, service::bootstrap);
        }

        @Test
        @DisplayName("should throw when TTL is negative")
        void shouldThrowWhenTtlIsNegative() {
            var config = createConfig(true, false, Optional.of(VALID_BOOTSTRAP_KEY), Duration.ofHours(-1));
            var service = createService(config);

            assertThrows(BootstrapException.class, service::bootstrap);
        }
    }
}
