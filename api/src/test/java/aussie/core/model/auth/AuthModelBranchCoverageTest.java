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
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Auth model behavioral tests")
class AuthModelBranchCoverageTest {

    @Nested
    @DisplayName("AuthenticationContext")
    class AuthenticationContextTest {

        private final Principal principal = Principal.user("u1", "User");

        @Test
        @DisplayName("Should return true when principal has exact permission")
        void shouldReturnTrueWhenHasExactPermission() {
            var ctx = new AuthenticationContext(principal, Set.of("read", "write"), Map.of(), Instant.now(), null);
            assertTrue(ctx.hasPermission("read"));
        }

        @Test
        @DisplayName("Should return false when principal is missing permission")
        void shouldReturnFalseWhenMissingPermission() {
            var ctx = new AuthenticationContext(principal, Set.of("read"), Map.of(), Instant.now(), null);
            assertFalse(ctx.hasPermission("write"));
        }

        @Test
        @DisplayName("Should grant any permission when wildcard is present")
        void shouldReturnTrueWhenWildcardPermission() {
            var ctx = new AuthenticationContext(principal, Set.of("*"), Map.of(), Instant.now(), null);
            assertTrue(ctx.hasPermission("anything"));
        }

        @Test
        @DisplayName("Should not be expired when expiresAt is null")
        void shouldReturnFalseWhenNotExpiredWithNullExpiresAt() {
            var ctx = new AuthenticationContext(principal, Set.of(), Map.of(), Instant.now(), null);
            assertFalse(ctx.isExpired());
        }

        @Test
        @DisplayName("Should not be expired when expiresAt is in the future")
        void shouldReturnFalseWhenNotExpiredWithFutureExpiresAt() {
            var ctx = new AuthenticationContext(
                    principal, Set.of(), Map.of(), Instant.now(), Instant.now().plusSeconds(3600));
            assertFalse(ctx.isExpired());
        }

        @Test
        @DisplayName("Should be expired when expiresAt is in the past")
        void shouldReturnTrueWhenExpired() {
            var ctx = new AuthenticationContext(
                    principal, Set.of(), Map.of(), Instant.now(), Instant.now().minusSeconds(10));
            assertTrue(ctx.isExpired());
        }

        @Test
        @DisplayName("Should build context with all fields via builder")
        void shouldBuildWithAllFields() {
            var now = Instant.now();
            var expires = now.plusSeconds(60);
            var ctx = AuthenticationContext.builder(principal)
                    .permissions(Set.of("p1"))
                    .claims(Map.of("k", "v"))
                    .authenticatedAt(now)
                    .expiresAt(expires)
                    .build();

            assertEquals(principal, ctx.principal());
            assertEquals(Set.of("p1"), ctx.permissions());
            assertEquals(Map.of("k", "v"), ctx.claims());
            assertEquals(now, ctx.authenticatedAt());
            assertEquals(expires, ctx.expiresAt());
        }

        @Test
        @DisplayName("Should default to empty permissions and claims when using builder defaults")
        void shouldBuildWithDefaults() {
            var ctx = AuthenticationContext.builder(principal).build();
            assertNotNull(ctx.authenticatedAt());
            assertTrue(ctx.permissions().isEmpty());
            assertTrue(ctx.claims().isEmpty());
        }

        @Test
        @DisplayName("Should throw on null principal")
        void shouldThrowOnNullPrincipal() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AuthenticationContext(null, Set.of(), Map.of(), Instant.now(), null));
        }

        @Test
        @DisplayName("Should default null permissions, claims, and authenticatedAt")
        void shouldDefaultNullFields() {
            var ctx = new AuthenticationContext(principal, null, null, null, null);
            assertTrue(ctx.permissions().isEmpty());
            assertTrue(ctx.claims().isEmpty());
            assertNotNull(ctx.authenticatedAt());
        }
    }

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
    @DisplayName("AuthenticationResult")
    class AuthenticationResultTest {

        @Nested
        @DisplayName("Success")
        class SuccessTest {

            @Test
            @DisplayName("Should throw on null context")
            void shouldThrowOnNullContext() {
                assertThrows(IllegalArgumentException.class, () -> new AuthenticationResult.Success(null));
            }

            @Test
            @DisplayName("Should wrap valid context")
            void shouldWrapValidContext() {
                var principal = Principal.user("u1", "User");
                var ctx = AuthenticationContext.builder(principal).build();
                var success = new AuthenticationResult.Success(ctx);
                assertEquals(ctx, success.context());
            }
        }

        @Nested
        @DisplayName("Failure")
        class FailureTest {

            @Test
            @DisplayName("Should default status code to 401 when given code below 400")
            void shouldDefaultStatusCodeWhenBelow400() {
                var failure = new AuthenticationResult.Failure("reason", 200);
                assertEquals(401, failure.statusCode());
            }

            @Test
            @DisplayName("Should default status code to 401 when given code at or above 500")
            void shouldDefaultStatusCodeWhenAtOrAbove500() {
                var failure = new AuthenticationResult.Failure("reason", 500);
                assertEquals(401, failure.statusCode());
            }

            @Test
            @DisplayName("Should accept valid 4xx status code")
            void shouldAcceptValidStatusCode() {
                var failure = new AuthenticationResult.Failure("reason", 403);
                assertEquals(403, failure.statusCode());
            }

            @Test
            @DisplayName("Should accept status code 499 as valid upper bound")
            void shouldAcceptStatusCode499() {
                var failure = new AuthenticationResult.Failure("reason", 499);
                assertEquals(499, failure.statusCode());
            }

            @Test
            @DisplayName("Should default null reason to 'Authentication failed'")
            void shouldDefaultNullReason() {
                var failure = new AuthenticationResult.Failure(null, 401);
                assertEquals("Authentication failed", failure.reason());
            }

            @Test
            @DisplayName("Should default blank reason to 'Authentication failed'")
            void shouldDefaultBlankReason() {
                var failure = new AuthenticationResult.Failure("  ", 403);
                assertEquals("Authentication failed", failure.reason());
            }

            @Test
            @DisplayName("Should create unauthorized failure with 401 status")
            void shouldCreateUnauthorized() {
                var failure = AuthenticationResult.Failure.unauthorized("bad token");
                assertEquals(401, failure.statusCode());
                assertEquals("bad token", failure.reason());
            }

            @Test
            @DisplayName("Should create forbidden failure with 403 status")
            void shouldCreateForbidden() {
                var failure = AuthenticationResult.Failure.forbidden("no access");
                assertEquals(403, failure.statusCode());
                assertEquals("no access", failure.reason());
            }
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
