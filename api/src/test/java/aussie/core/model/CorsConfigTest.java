package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CorsConfig Tests")
class CorsConfigTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("allowAll() should create permissive config")
        void allowAllShouldCreatePermissiveConfig() {
            var config = CorsConfig.allowAll();

            assertTrue(config.isOriginAllowed("http://example.com"));
            assertTrue(config.isOriginAllowed("http://any-origin.test"));
            assertTrue(config.isMethodAllowed("GET"));
            assertTrue(config.isMethodAllowed("DELETE"));
            assertTrue(config.allowCredentials());
            assertEquals(Optional.of(3600L), config.maxAge());
        }

        @Test
        @DisplayName("denyAll() should create restrictive config")
        void denyAllShouldCreateRestrictiveConfig() {
            var config = CorsConfig.denyAll();

            assertFalse(config.isOriginAllowed("http://example.com"));
            assertFalse(config.isMethodAllowed("GET"));
            assertFalse(config.allowCredentials());
            assertEquals(Optional.empty(), config.maxAge());
        }
    }

    @Nested
    @DisplayName("isOriginAllowed()")
    class IsOriginAllowedTests {

        @Test
        @DisplayName("Should return false for null origin")
        void shouldReturnFalseForNullOrigin() {
            var config =
                    CorsConfig.builder().allowedOrigins("http://example.com").build();

            assertFalse(config.isOriginAllowed(null));
        }

        @Test
        @DisplayName("Should return false for blank origin")
        void shouldReturnFalseForBlankOrigin() {
            var config =
                    CorsConfig.builder().allowedOrigins("http://example.com").build();

            assertFalse(config.isOriginAllowed(""));
            assertFalse(config.isOriginAllowed("   "));
        }

        @Test
        @DisplayName("Should return false when no origins configured")
        void shouldReturnFalseWhenNoOriginsConfigured() {
            var config = CorsConfig.builder().build();

            assertFalse(config.isOriginAllowed("http://example.com"));
        }

        @Test
        @DisplayName("Should allow wildcard origin")
        void shouldAllowWildcardOrigin() {
            var config = CorsConfig.builder().allowedOrigins("*").build();

            assertTrue(config.isOriginAllowed("http://example.com"));
            assertTrue(config.isOriginAllowed("http://any-domain.test"));
        }

        @Test
        @DisplayName("Should match exact origin")
        void shouldMatchExactOrigin() {
            var config = CorsConfig.builder()
                    .allowedOrigins("http://example.com", "http://test.com")
                    .build();

            assertTrue(config.isOriginAllowed("http://example.com"));
            assertTrue(config.isOriginAllowed("http://test.com"));
            assertFalse(config.isOriginAllowed("http://other.com"));
        }

        @Test
        @DisplayName("Should match wildcard subdomain pattern")
        void shouldMatchWildcardSubdomainPattern() {
            var config = CorsConfig.builder().allowedOrigins("*.example.com").build();

            assertTrue(config.isOriginAllowed("http://api.example.com"));
            assertTrue(config.isOriginAllowed("https://sub.example.com"));
            assertFalse(config.isOriginAllowed("http://example.com"));
            assertFalse(config.isOriginAllowed("http://other.com"));
        }
    }

    @Nested
    @DisplayName("isMethodAllowed()")
    class IsMethodAllowedTests {

        @Test
        @DisplayName("Should return false for null method")
        void shouldReturnFalseForNullMethod() {
            var config = CorsConfig.builder().allowedMethods("GET", "POST").build();

            assertFalse(config.isMethodAllowed(null));
        }

        @Test
        @DisplayName("Should return false for blank method")
        void shouldReturnFalseForBlankMethod() {
            var config = CorsConfig.builder().allowedMethods("GET", "POST").build();

            assertFalse(config.isMethodAllowed(""));
            assertFalse(config.isMethodAllowed("   "));
        }

        @Test
        @DisplayName("Should allow wildcard method")
        void shouldAllowWildcardMethod() {
            var config = CorsConfig.builder().allowedMethods("*").build();

            assertTrue(config.isMethodAllowed("GET"));
            assertTrue(config.isMethodAllowed("POST"));
            assertTrue(config.isMethodAllowed("CUSTOM"));
        }

        @Test
        @DisplayName("Should match method case-insensitively")
        void shouldMatchMethodCaseInsensitively() {
            var config = CorsConfig.builder().allowedMethods("GET", "POST").build();

            assertTrue(config.isMethodAllowed("get"));
            assertTrue(config.isMethodAllowed("Get"));
            assertTrue(config.isMethodAllowed("GET"));
        }

        @Test
        @DisplayName("Should reject unallowed methods")
        void shouldRejectUnallowedMethods() {
            var config = CorsConfig.builder().allowedMethods("GET", "POST").build();

            assertTrue(config.isMethodAllowed("GET"));
            assertTrue(config.isMethodAllowed("POST"));
            assertFalse(config.isMethodAllowed("DELETE"));
        }
    }

    @Nested
    @DisplayName("String Formatters")
    class StringFormatterTests {

        @Test
        @DisplayName("getAllowedHeadersString() should return wildcard")
        void getAllowedHeadersStringShouldReturnWildcard() {
            var config = CorsConfig.builder().allowedHeaders("*").build();

            assertEquals("*", config.getAllowedHeadersString());
        }

        @Test
        @DisplayName("getAllowedHeadersString() should join headers")
        void getAllowedHeadersStringShouldJoinHeaders() {
            var config = CorsConfig.builder()
                    .allowedHeaders("Content-Type", "Authorization")
                    .build();

            var result = config.getAllowedHeadersString();
            assertTrue(result.contains("Content-Type"));
            assertTrue(result.contains("Authorization"));
        }

        @Test
        @DisplayName("getAllowedMethodsString() should return standard methods for wildcard")
        void getAllowedMethodsStringShouldReturnStandardMethodsForWildcard() {
            var config = CorsConfig.builder().allowedMethods("*").build();

            var result = config.getAllowedMethodsString();
            assertTrue(result.contains("GET"));
            assertTrue(result.contains("POST"));
            assertTrue(result.contains("DELETE"));
        }

        @Test
        @DisplayName("getExposedHeadersString() should return null when empty")
        void getExposedHeadersStringShouldReturnNullWhenEmpty() {
            var config = CorsConfig.builder().build();

            assertNull(config.getExposedHeadersString());
        }

        @Test
        @DisplayName("getExposedHeadersString() should join exposed headers")
        void getExposedHeadersStringShouldJoinExposedHeaders() {
            var config = CorsConfig.builder()
                    .exposedHeaders("X-Request-Id", "X-Custom")
                    .build();

            var result = config.getExposedHeadersString();
            assertNotNull(result);
            assertTrue(result.contains("X-Request-Id"));
            assertTrue(result.contains("X-Custom"));
        }
    }

    @Nested
    @DisplayName("mergeWith()")
    class MergeWithTests {

        @Test
        @DisplayName("Should return this when override is null")
        void shouldReturnThisWhenOverrideIsNull() {
            var config =
                    CorsConfig.builder().allowedOrigins("http://example.com").build();

            var result = config.mergeWith(null);

            assertEquals(config, result);
        }

        @Test
        @DisplayName("Should use override values when present")
        void shouldUseOverrideValuesWhenPresent() {
            var base = CorsConfig.builder()
                    .allowedOrigins("http://base.com")
                    .allowedMethods("GET")
                    .allowCredentials(false)
                    .build();

            var override = CorsConfig.builder()
                    .allowedOrigins("http://override.com")
                    .allowedMethods("POST")
                    .allowCredentials(true)
                    .build();

            var result = base.mergeWith(override);

            assertTrue(result.isOriginAllowed("http://override.com"));
            assertFalse(result.isOriginAllowed("http://base.com"));
            assertTrue(result.isMethodAllowed("POST"));
            assertTrue(result.allowCredentials());
        }

        @Test
        @DisplayName("Should keep base values when override is empty")
        void shouldKeepBaseValuesWhenOverrideIsEmpty() {
            var base = CorsConfig.builder()
                    .allowedOrigins("http://base.com")
                    .allowedMethods("GET")
                    .maxAge(3600)
                    .build();

            var override = CorsConfig.builder().build();

            var result = base.mergeWith(override);

            assertTrue(result.isOriginAllowed("http://base.com"));
            assertTrue(result.isMethodAllowed("GET"));
            assertEquals(Optional.of(3600L), result.maxAge());
        }
    }

    @Nested
    @DisplayName("Record Compact Constructor")
    class CompactConstructorTests {

        @Test
        @DisplayName("Should provide default values for null fields")
        void shouldProvideDefaultValuesForNullFields() {
            var config = new CorsConfig(null, null, null, null, false, null);

            assertEquals(List.of(), config.allowedOrigins());
            assertNotNull(config.allowedMethods());
            assertNotNull(config.allowedHeaders());
            assertEquals(Set.of(), config.exposedHeaders());
            assertEquals(Optional.empty(), config.maxAge());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build config with all fields")
        void shouldBuildConfigWithAllFields() {
            var config = CorsConfig.builder()
                    .allowedOrigins("http://example.com")
                    .allowedMethods("GET", "POST")
                    .allowedHeaders("Content-Type")
                    .exposedHeaders("X-Request-Id")
                    .allowCredentials(true)
                    .maxAge(7200)
                    .build();

            assertTrue(config.isOriginAllowed("http://example.com"));
            assertTrue(config.isMethodAllowed("GET"));
            assertTrue(config.isMethodAllowed("POST"));
            assertTrue(config.allowCredentials());
            assertEquals(Optional.of(7200L), config.maxAge());
        }

        @Test
        @DisplayName("Should accept List for allowedOrigins")
        void shouldAcceptListForAllowedOrigins() {
            var config = CorsConfig.builder()
                    .allowedOrigins(List.of("http://a.com", "http://b.com"))
                    .build();

            assertTrue(config.isOriginAllowed("http://a.com"));
            assertTrue(config.isOriginAllowed("http://b.com"));
        }

        @Test
        @DisplayName("Should accept Set for allowedMethods")
        void shouldAcceptSetForAllowedMethods() {
            var config =
                    CorsConfig.builder().allowedMethods(Set.of("GET", "POST")).build();

            assertTrue(config.isMethodAllowed("GET"));
            assertTrue(config.isMethodAllowed("POST"));
        }
    }
}
