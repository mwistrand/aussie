package aussie.core.model.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceSecurityHeadersConfig")
class ServiceSecurityHeadersConfigTest {

    @Nested
    @DisplayName("Compact constructor")
    class CompactConstructor {

        @Test
        @DisplayName("should default null fields to empty Optional")
        void shouldDefaultNullFieldsToEmptyOptional() {
            final var config = new ServiceSecurityHeadersConfig(null, null, null, null, null, null, null, null);

            assertTrue(config.contentTypeOptions().isEmpty());
            assertTrue(config.frameOptions().isEmpty());
            assertTrue(config.contentSecurityPolicy().isEmpty());
            assertTrue(config.referrerPolicy().isEmpty());
            assertTrue(config.permittedCrossDomainPolicies().isEmpty());
            assertTrue(config.strictTransportSecurity().isEmpty());
            assertTrue(config.permissionsPolicy().isEmpty());
            assertTrue(config.customHeaders().isEmpty());
        }

        @Test
        @DisplayName("should preserve non-null values")
        void shouldPreserveNonNullValues() {
            final var config = new ServiceSecurityHeadersConfig(
                    Optional.of("nosniff"),
                    Optional.of("SAMEORIGIN"),
                    Optional.of("default-src 'self'"),
                    Optional.of("no-referrer"),
                    Optional.of("none"),
                    Optional.of("max-age=31536000"),
                    Optional.of("camera=()"),
                    Map.of("X-Custom", "value"));

            assertEquals("nosniff", config.contentTypeOptions().orElseThrow());
            assertEquals("SAMEORIGIN", config.frameOptions().orElseThrow());
            assertEquals("default-src 'self'", config.contentSecurityPolicy().orElseThrow());
            assertEquals("no-referrer", config.referrerPolicy().orElseThrow());
            assertEquals("none", config.permittedCrossDomainPolicies().orElseThrow());
            assertEquals("max-age=31536000", config.strictTransportSecurity().orElseThrow());
            assertEquals("camera=()", config.permissionsPolicy().orElseThrow());
            assertEquals(Map.of("X-Custom", "value"), config.customHeaders());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with only overridden fields set")
        void shouldBuildWithPartialOverrides() {
            final var config = ServiceSecurityHeadersConfig.builder()
                    .contentSecurityPolicy("default-src 'self'")
                    .frameOptions("SAMEORIGIN")
                    .build();

            assertEquals(Optional.of("default-src 'self'"), config.contentSecurityPolicy());
            assertEquals(Optional.of("SAMEORIGIN"), config.frameOptions());
            assertTrue(config.contentTypeOptions().isEmpty());
            assertTrue(config.referrerPolicy().isEmpty());
            assertTrue(config.permittedCrossDomainPolicies().isEmpty());
            assertTrue(config.strictTransportSecurity().isEmpty());
            assertTrue(config.permissionsPolicy().isEmpty());
            assertTrue(config.customHeaders().isEmpty());
        }

        @Test
        @DisplayName("should build with custom headers")
        void shouldBuildWithCustomHeaders() {
            final var headers = Map.of("X-Custom-Header", "value1", "X-Another", "value2");
            final var config = ServiceSecurityHeadersConfig.builder()
                    .customHeaders(headers)
                    .build();

            assertEquals(headers, config.customHeaders());
        }

        @Test
        @DisplayName("should build with all fields set")
        void shouldBuildWithAllFields() {
            final var config = ServiceSecurityHeadersConfig.builder()
                    .contentTypeOptions("nosniff")
                    .frameOptions("DENY")
                    .contentSecurityPolicy("default-src 'none'")
                    .referrerPolicy("strict-origin")
                    .permittedCrossDomainPolicies("none")
                    .strictTransportSecurity("max-age=31536000")
                    .permissionsPolicy("camera=()")
                    .customHeaders(Map.of("X-Custom", "val"))
                    .build();

            assertEquals(Optional.of("nosniff"), config.contentTypeOptions());
            assertEquals(Optional.of("DENY"), config.frameOptions());
            assertEquals(Optional.of("default-src 'none'"), config.contentSecurityPolicy());
            assertEquals(Optional.of("strict-origin"), config.referrerPolicy());
            assertEquals(Optional.of("none"), config.permittedCrossDomainPolicies());
            assertEquals(Optional.of("max-age=31536000"), config.strictTransportSecurity());
            assertEquals(Optional.of("camera=()"), config.permissionsPolicy());
            assertEquals(Map.of("X-Custom", "val"), config.customHeaders());
        }

        @Test
        @DisplayName("should build empty config with no fields set")
        void shouldBuildEmptyConfig() {
            final var config = ServiceSecurityHeadersConfig.builder().build();

            assertTrue(config.contentTypeOptions().isEmpty());
            assertTrue(config.frameOptions().isEmpty());
            assertTrue(config.contentSecurityPolicy().isEmpty());
            assertTrue(config.referrerPolicy().isEmpty());
            assertTrue(config.permittedCrossDomainPolicies().isEmpty());
            assertTrue(config.strictTransportSecurity().isEmpty());
            assertTrue(config.permissionsPolicy().isEmpty());
            assertTrue(config.customHeaders().isEmpty());
        }

        @Test
        @DisplayName("should support empty string for header suppression")
        void shouldSupportEmptyStringForSuppression() {
            final var config = ServiceSecurityHeadersConfig.builder()
                    .contentSecurityPolicy("")
                    .build();

            assertTrue(config.contentSecurityPolicy().isPresent());
            assertEquals("", config.contentSecurityPolicy().orElseThrow());
        }
    }
}
