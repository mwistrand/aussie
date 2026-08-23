package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Auth model behavioral tests")
class AuthModelBranchCoverageTest {

    @Nested
    @DisplayName("ApiKey")
    class ApiKeyTest {

        @Test
        @DisplayName("Should be valid when not revoked and has no expiry")
        void shouldBeValidWhenNotRevokedAndNotExpired() {
            var key = new ApiKey("k1", "hash", "name", "", null, Set.of(), "admin", Instant.now(), null, false);
            assertTrue(key.isValid());
        }

        @Test
        @DisplayName("Should be valid when not revoked and expiry is in the future")
        void shouldBeValidWhenNotRevokedAndFutureExpiry() {
            var key = new ApiKey(
                    "k1",
                    "hash",
                    "name",
                    "",
                    null,
                    Set.of(),
                    "admin",
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    false);
            assertTrue(key.isValid());
        }

        @Test
        @DisplayName("Should be invalid when revoked")
        void shouldBeInvalidWhenRevoked() {
            var key = new ApiKey("k1", "hash", "name", "", null, Set.of(), "admin", Instant.now(), null, true);
            assertFalse(key.isValid());
        }

        @Test
        @DisplayName("Should be invalid when expired")
        void shouldBeInvalidWhenExpired() {
            var key = new ApiKey(
                    "k1",
                    "hash",
                    "name",
                    "",
                    null,
                    Set.of(),
                    "admin",
                    Instant.now(),
                    Instant.now().minusSeconds(10),
                    false);
            assertFalse(key.isValid());
        }

        @Test
        @DisplayName("Should redact key hash in redacted copy")
        void shouldRedactKeyHash() {
            var key = new ApiKey(
                    "k1", "secret-hash", "name", "desc", "team", Set.of("read"), "admin", Instant.now(), null, false);
            var redacted = key.redacted();
            assertEquals("[REDACTED]", redacted.keyHash());
            assertEquals("k1", redacted.id());
            assertEquals("name", redacted.name());
        }

        @Test
        @DisplayName("Should mark key as revoked")
        void shouldRevokeKey() {
            var key = new ApiKey("k1", "hash", "name", "", null, Set.of(), "admin", Instant.now(), null, false);
            var revoked = key.revoke();
            assertTrue(revoked.revoked());
            assertEquals("k1", revoked.id());
        }

        @Test
        @DisplayName("Should throw on null id")
        void shouldThrowOnNullId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ApiKey(null, "hash", "name", "", null, Set.of(), "admin", Instant.now(), null, false));
        }

        @Test
        @DisplayName("Should throw on blank keyHash")
        void shouldThrowOnBlankKeyHash() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ApiKey("k1", "  ", "name", "", null, Set.of(), "admin", Instant.now(), null, false));
        }

        @Test
        @DisplayName("Should default null optional fields")
        void shouldDefaultNullOptionalFields() {
            var key = new ApiKey("k1", "hash", null, null, null, null, null, null, null, false);
            assertEquals("k1", key.name()); // defaults to id
            assertEquals("", key.description());
            assertTrue(key.permissions().isEmpty());
            assertEquals("unknown", key.createdBy());
            assertNotNull(key.createdAt());
        }
    }

    @Nested
    @DisplayName("SigningKeyRecord")
    class SigningKeyRecordTest {

        private static RSAPublicKey publicKey;
        private static RSAPrivateKey privateKey;

        @BeforeAll
        static void generateKeyPair() throws Exception {
            final var keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            final KeyPair keyPair = keyPairGen.generateKeyPair();
            publicKey = (RSAPublicKey) keyPair.getPublic();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
        }

        @Test
        @DisplayName("Should activate a pending key")
        void shouldActivatePendingKey() {
            var key = createPendingKey();
            var now = Instant.now();
            var activated = key.activate(now);
            assertEquals(KeyStatus.ACTIVE, activated.status());
            assertEquals(now, activated.activatedAt());
        }

        @Test
        @DisplayName("Should activate with current timestamp when null is provided")
        void shouldActivateWithDefaultTimestampWhenNull() {
            var key = createPendingKey();
            var activated = key.activate(null);
            assertEquals(KeyStatus.ACTIVE, activated.status());
            assertNotNull(activated.activatedAt());
        }

        @Test
        @DisplayName("Should throw when activating a non-pending key")
        void shouldThrowWhenActivatingNonPendingKey() {
            var key = createActiveKey();
            assertThrows(IllegalStateException.class, () -> key.activate(Instant.now()));
        }

        @Test
        @DisplayName("Should deprecate an active key")
        void shouldDeprecateActiveKey() {
            var key = createActiveKey();
            var now = Instant.now();
            var deprecated = key.deprecate(now);
            assertEquals(KeyStatus.DEPRECATED, deprecated.status());
            assertEquals(now, deprecated.deprecatedAt());
        }

        @Test
        @DisplayName("Should deprecate with current timestamp when null is provided")
        void shouldDeprecateWithDefaultTimestampWhenNull() {
            var key = createActiveKey();
            var deprecated = key.deprecate(null);
            assertEquals(KeyStatus.DEPRECATED, deprecated.status());
            assertNotNull(deprecated.deprecatedAt());
        }

        @Test
        @DisplayName("Should throw when deprecating a non-active key")
        void shouldThrowWhenDeprecatingNonActiveKey() {
            var key = createPendingKey();
            assertThrows(IllegalStateException.class, () -> key.deprecate(Instant.now()));
        }

        @Test
        @DisplayName("Should retire an active key")
        void shouldRetireActiveKey() {
            var key = createActiveKey();
            var now = Instant.now();
            var retired = key.retire(now);
            assertEquals(KeyStatus.RETIRED, retired.status());
            assertEquals(now, retired.retiredAt());
        }

        @Test
        @DisplayName("Should retire a deprecated key")
        void shouldRetireDeprecatedKey() {
            var key = createActiveKey().deprecate(Instant.now());
            var now = Instant.now();
            var retired = key.retire(now);
            assertEquals(KeyStatus.RETIRED, retired.status());
            assertEquals(now, retired.retiredAt());
        }

        @Test
        @DisplayName("Should retire with current timestamp when null is provided")
        void shouldRetireWithDefaultTimestampWhenNull() {
            var key = createActiveKey();
            var retired = key.retire(null);
            assertEquals(KeyStatus.RETIRED, retired.status());
            assertNotNull(retired.retiredAt());
        }

        @Test
        @DisplayName("Should throw when retiring a pending key")
        void shouldThrowWhenRetiringPendingKey() {
            var key = createPendingKey();
            assertThrows(IllegalStateException.class, () -> key.retire(Instant.now()));
        }

        @Test
        @DisplayName("Should throw when retiring an already retired key")
        void shouldThrowWhenRetiringRetiredKey() {
            var key = createActiveKey().retire(Instant.now());
            assertThrows(IllegalStateException.class, () -> key.retire(Instant.now()));
        }

        @Test
        @DisplayName("Should allow signing when active with private key")
        void shouldCanSignWhenActiveWithPrivateKey() {
            var key = createActiveKey();
            assertTrue(key.canSign());
        }

        @Test
        @DisplayName("Should not allow signing when pending")
        void shouldNotCanSignWhenPending() {
            var key = createPendingKey();
            assertFalse(key.canSign());
        }

        @Test
        @DisplayName("Should not allow signing when private key is absent")
        void shouldNotCanSignWhenNoPrivateKey() {
            var key = createActiveKey().withoutPrivateKey();
            assertFalse(key.canSign());
        }

        @Test
        @DisplayName("Should allow verification when active")
        void shouldCanVerifyWhenActive() {
            var key = createActiveKey();
            assertTrue(key.canVerify());
        }

        @Test
        @DisplayName("Should allow verification when deprecated")
        void shouldCanVerifyWhenDeprecated() {
            var key = createActiveKey().deprecate(Instant.now());
            assertTrue(key.canVerify());
        }

        @Test
        @DisplayName("Should not allow verification when pending")
        void shouldNotCanVerifyWhenPending() {
            var key = createPendingKey();
            assertFalse(key.canVerify());
        }

        @Test
        @DisplayName("Should not allow verification when retired")
        void shouldNotCanVerifyWhenRetired() {
            var key = createActiveKey().retire(Instant.now());
            assertFalse(key.canVerify());
        }

        @Test
        @DisplayName("Should create copy without private key preserving other fields")
        void shouldCreateWithoutPrivateKey() {
            var key = createActiveKey();
            var verifyOnly = key.withoutPrivateKey();
            assertNotNull(verifyOnly.publicKey());
            assertEquals(key.keyId(), verifyOnly.keyId());
            assertEquals(key.status(), verifyOnly.status());
        }

        @Test
        @DisplayName("Should preserve deprecatedAt when retiring a deprecated key")
        void shouldRetireDeprecatedKeyPreservingDeprecatedAt() {
            var key = createActiveKey();
            var deprecatedAt = Instant.now().minusSeconds(100);
            var deprecated = key.deprecate(deprecatedAt);
            var retiredAt = Instant.now();
            var retired = deprecated.retire(retiredAt);
            assertEquals(deprecatedAt, retired.deprecatedAt());
            assertEquals(retiredAt, retired.retiredAt());
        }

        @Test
        @DisplayName("Should set deprecatedAt equal to retiredAt when retiring an active key directly")
        void shouldRetireActiveKeySettingDeprecatedAtToRetiredAt() {
            var key = createActiveKey();
            var retiredAt = Instant.now();
            var retired = key.retire(retiredAt);
            assertEquals(retiredAt, retired.deprecatedAt());
            assertEquals(retiredAt, retired.retiredAt());
        }

        private SigningKeyRecord createPendingKey() {
            return SigningKeyRecord.pending("kid-1", privateKey, publicKey);
        }

        private SigningKeyRecord createActiveKey() {
            return SigningKeyRecord.active("kid-1", privateKey, publicKey);
        }
    }

    @Nested
    @DisplayName("TranslationConfigSchema.Defaults")
    class DefaultsTest {

        @Test
        @DisplayName("secure() should deny unmatched and exclude unmapped")
        void secureShouldDenyAndExclude() {
            var defaults = TranslationConfigSchema.Defaults.secure();
            assertTrue(defaults.denyIfNoMatch());
            assertFalse(defaults.includeUnmapped());
        }
    }

    @Nested
    @DisplayName("Permission")
    class PermissionTest {

        @Test
        @DisplayName("toRole should return null for null input")
        void toRoleShouldReturnNullForNull() {
            var result = Permission.toRole(null);
            assertEquals(null, result);
        }

        @Test
        @DisplayName("toRoles should resolve service-scoped permissions to un-scoped equivalents")
        void toRolesShouldResolveScopedPermissions() {
            var roles = Permission.toRoles(Set.of("my-service.config.update"));
            assertTrue(roles.contains("my-service.config.update"));
            assertTrue(roles.contains("service.config.update"));
        }

        @Test
        @DisplayName("toRoles should not duplicate when permission is already un-scoped")
        void toRolesShouldNotDuplicateUnscoped() {
            var roles = Permission.toRoles(Set.of("service.config.read"));
            assertTrue(roles.contains("service.config.read"));
        }

        @Test
        @DisplayName("toRoles should return empty set for null input")
        void toRolesShouldReturnEmptyForNull() {
            assertTrue(Permission.toRoles(null).isEmpty());
        }
    }
}
