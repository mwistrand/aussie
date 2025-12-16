package aussie.core.model.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("JwsConfig")
class JwsConfigTest {

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("should reject null issuer")
        void shouldRejectNullIssuer() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JwsConfig(null, "keyId", Duration.ofMinutes(5), Set.of()));
        }

        @Test
        @DisplayName("should reject blank issuer")
        void shouldRejectBlankIssuer() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JwsConfig("   ", "keyId", Duration.ofMinutes(5), Set.of()));
        }

        @Test
        @DisplayName("should reject null keyId")
        void shouldRejectNullKeyId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JwsConfig("issuer", null, Duration.ofMinutes(5), Set.of()));
        }

        @Test
        @DisplayName("should reject blank keyId")
        void shouldRejectBlankKeyId() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new JwsConfig("issuer", "   ", Duration.ofMinutes(5), Set.of()));
        }

        @Test
        @DisplayName("should default tokenTtl to 5 minutes when null")
        void shouldDefaultTokenTtl() {
            final var config = new JwsConfig("issuer", "keyId", null, Set.of());
            assertEquals(Duration.ofMinutes(5), config.tokenTtl());
        }

        @Test
        @DisplayName("should default maxTokenTtl to 24 hours when null")
        void shouldDefaultMaxTokenTtl() {
            final var config = new JwsConfig("issuer", "keyId", Duration.ofMinutes(5), null, Set.of());
            assertEquals(Duration.ofHours(24), config.maxTokenTtl());
        }

        @Test
        @DisplayName("should default forwardedClaims when null")
        void shouldDefaultForwardedClaims() {
            final var config = new JwsConfig("issuer", "keyId", Duration.ofMinutes(5), Set.of("sub", "email", "name"));
            assertEquals(Set.of("sub", "email", "name"), config.forwardedClaims());
        }
    }

    @Nested
    @DisplayName("effectiveTtl()")
    class EffectiveTtlTests {

        private final JwsConfig config =
                new JwsConfig("issuer", "keyId", Duration.ofMinutes(5), Duration.ofHours(1), Set.of());

        @Test
        @DisplayName("should return default TTL when requested is null")
        void shouldReturnDefaultWhenNull() {
            assertEquals(Duration.ofMinutes(5), config.effectiveTtl(null));
        }

        @Test
        @DisplayName("should return requested TTL when less than max")
        void shouldReturnRequestedWhenLessThanMax() {
            final var requested = Duration.ofMinutes(30);
            assertEquals(requested, config.effectiveTtl(requested));
        }

        @Test
        @DisplayName("should clamp to max TTL when requested exceeds max")
        void shouldClampToMaxWhenExceedsMax() {
            final var requested = Duration.ofHours(2);
            assertEquals(Duration.ofHours(1), config.effectiveTtl(requested));
        }

        @Test
        @DisplayName("should return max TTL when requested equals max")
        void shouldReturnRequestedWhenEqualsMax() {
            final var requested = Duration.ofHours(1);
            assertEquals(requested, config.effectiveTtl(requested));
        }

        @Test
        @DisplayName("should allow very short TTL")
        void shouldAllowVeryShortTtl() {
            final var requested = Duration.ofSeconds(30);
            assertEquals(requested, config.effectiveTtl(requested));
        }
    }

    @Nested
    @DisplayName("defaults()")
    class DefaultsTests {

        @Test
        @DisplayName("should create valid defaults config")
        void shouldCreateValidDefaults() {
            final var config = JwsConfig.defaults();

            assertEquals("aussie-gateway", config.issuer());
            assertEquals("v1", config.keyId());
            assertEquals(Duration.ofMinutes(5), config.tokenTtl());
            assertEquals(Duration.ofHours(24), config.maxTokenTtl());
            assertEquals(
                    Set.of("sub", "email", "name", "groups", "roles", "effective_permissions"),
                    config.forwardedClaims());
        }
    }

    @Nested
    @DisplayName("backward compatibility constructor")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("4-arg constructor should default maxTokenTtl to 24 hours")
        void fourArgConstructorShouldDefaultMaxTtl() {
            final var config = new JwsConfig("issuer", "keyId", Duration.ofMinutes(10), Set.of("sub"));

            assertEquals(Duration.ofHours(24), config.maxTokenTtl());
            assertEquals(Duration.ofMinutes(10), config.tokenTtl());
        }
    }
}
