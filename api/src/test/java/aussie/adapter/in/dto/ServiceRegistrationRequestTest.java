package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.routing.EndpointVisibility;

@DisplayName("ServiceRegistrationRequest Tests")
class ServiceRegistrationRequestTest {

    @Nested
    @DisplayName("toModel() - defaultAuthRequired inheritance")
    class DefaultAuthRequiredInheritanceTests {

        @Test
        @DisplayName("Endpoints should inherit defaultAuthRequired=true when not specified")
        void shouldInheritDefaultAuthRequiredTrue() {
            var endpoint =
                    new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    "Test Service",
                    "http://api.example.com:8080",
                    null,
                    null,
                    true,
                    null,
                    List.of(endpoint),
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(1, model.endpoints().size());
            assertTrue(model.endpoints().get(0).authRequired());
        }

        @Test
        @DisplayName("Endpoints should inherit defaultAuthRequired=false when not specified")
        void shouldInheritDefaultAuthRequiredFalse() {
            var endpoint =
                    new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    "Test Service",
                    "http://api.example.com:8080",
                    null,
                    null,
                    false,
                    null,
                    List.of(endpoint),
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(1, model.endpoints().size());
            assertFalse(model.endpoints().get(0).authRequired());
        }

        @Test
        @DisplayName("Endpoints with explicit authRequired should override default")
        void shouldOverrideDefaultWithExplicitValue() {
            var publicEndpoint =
                    new EndpointConfigDto("/api/public", Set.of("GET"), "PUBLIC", null, false, null, null, null, null);
            var protectedEndpoint = new EndpointConfigDto(
                    "/api/protected", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    "Test Service",
                    "http://api.example.com:8080",
                    null,
                    null,
                    true,
                    null,
                    List.of(publicEndpoint, protectedEndpoint),
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(2, model.endpoints().size());
            assertFalse(model.endpoints().get(0).authRequired()); // explicit false
            assertTrue(model.endpoints().get(1).authRequired()); // inherited true
        }

        @Test
        @DisplayName("Should default defaultAuthRequired to true when not specified")
        void shouldDefaultToTrueWhenNotSpecified() {
            var endpoint =
                    new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, null, null, null, null, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    "Test Service",
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    List.of(endpoint),
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertTrue(model.defaultAuthRequired());
            assertTrue(model.endpoints().get(0).authRequired());
        }
    }

    @Nested
    @DisplayName("toModel() - other defaults")
    class OtherDefaultsTests {

        @Test
        @DisplayName("Should default visibility to PRIVATE when not specified")
        void shouldDefaultVisibilityToPrivate() {
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(EndpointVisibility.PRIVATE, model.defaultVisibility());
        }

        @Test
        @DisplayName("Should use serviceId as displayName when not specified")
        void shouldUseServiceIdAsDisplayName() {
            var request = new ServiceRegistrationRequest(
                    1L,
                    "my-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals("my-service", model.displayName());
        }

        @Test
        @DisplayName("Should parse visibility case-insensitively")
        void shouldParseVisibilityCaseInsensitively() {
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    "public",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(EndpointVisibility.PUBLIC, model.defaultVisibility());
        }

        @Test
        @DisplayName("Should default version to 1 when null")
        void shouldDefaultVersionToOneWhenNull() {
            var request = new ServiceRegistrationRequest(
                    null,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(1L, model.version());
        }

        @Test
        @DisplayName("Should convert visibility rules when present")
        void shouldConvertVisibilityRules() {
            var rule = new VisibilityRuleDto("/admin/**", List.of("GET"), "PRIVATE");
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    List.of(rule),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertEquals(1, model.visibilityRules().size());
            assertEquals("/admin/**", model.visibilityRules().get(0).pattern());
        }

        @Test
        @DisplayName("Should convert cors config when present")
        void shouldConvertCorsConfig() {
            var cors = new CorsConfigDto(List.of("https://example.com"), null, null, null, true, 3600L);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    cors,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertTrue(model.corsConfig().isPresent());
            assertEquals(
                    List.of("https://example.com"), model.corsConfig().get().allowedOrigins());
        }

        @Test
        @DisplayName("Should convert permission policy when present")
        void shouldConvertPermissionPolicy() {
            var policy = new ServicePermissionPolicyDto(Map.of("read", new OperationPermissionDto(Set.of("viewer"))));
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    policy,
                    null,
                    null);

            var model = request.toModel(true);

            assertTrue(model.permissionPolicy().isPresent());
        }

        @Test
        @DisplayName("Should convert rate limit config when present")
        void shouldConvertRateLimitConfig() {
            var rateLimit = new ServiceRateLimitConfigDto(100L, 60L, 50L, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimit,
                    null);

            var model = request.toModel(true);

            assertTrue(model.rateLimitConfig().isPresent());
        }

        @Test
        @DisplayName("Should convert sampling config when present")
        void shouldConvertSamplingConfig() {
            var sampling = new ServiceSamplingConfigDto(0.5);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    sampling);

            var model = request.toModel(true);

            assertTrue(model.samplingConfig().isPresent());
        }

        @Test
        @DisplayName("Should convert access config when present")
        void shouldConvertAccessConfig() {
            var accessConfig = new ServiceAccessConfigDto(List.of("10.0.0.1"), null, null);
            var request = new ServiceRegistrationRequest(
                    1L,
                    "test-service",
                    null,
                    "http://api.example.com:8080",
                    null,
                    null,
                    null,
                    null,
                    null,
                    accessConfig,
                    null,
                    null,
                    null,
                    null);

            var model = request.toModel(true);

            assertTrue(model.accessConfig().isPresent());
        }
    }
}
