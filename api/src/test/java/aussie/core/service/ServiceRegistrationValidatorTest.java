package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.RateLimitingConfig;
import aussie.core.model.auth.AccessControlConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.auth.ServiceAccessConfig;
import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.common.ValidationResult;
import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.ratelimit.ServiceWebSocketRateLimitConfig;
import aussie.core.model.ratelimit.ServiceWebSocketRateLimitConfig.RateLimitValues;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.model.timeout.EndpointTimeoutConfig;
import aussie.core.model.timeout.ServiceTimeoutConfig;
import aussie.core.service.routing.*;

@DisplayName("ServiceRegistrationValidator")
class ServiceRegistrationValidatorTest {

    private static final RateLimitingConfig PERMISSIVE_RATE_LIMIT_CONFIG = TestRateLimitingConfig.permissive();
    private static final aussie.core.config.ResiliencyConfig PERMISSIVE_RESILIENCY_CONFIG =
            TestResiliencyConfig.permissive();

    private ServiceRegistration.Builder createServiceBuilder() {
        return ServiceRegistration.builder("test-service").baseUrl("http://192.0.2.10:8080");
    }

    @Nested
    @DisplayName("Upstream Host Allowlist")
    class UpstreamHostAllowlistTests {

        @Test
        @DisplayName("Should reject every upstream when allowlist is absent")
        void shouldRejectWhenAllowlistAbsent() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withAllowedUpstreamHosts(null),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);

            var result = validator.validate(createServiceBuilder().build());

            assertInstanceOf(ValidationResult.Invalid.class, result);
            assertEquals(400, ((ValidationResult.Invalid) result).suggestedStatusCode());
        }

        @Test
        @DisplayName("Should reject a global wildcard")
        void shouldRejectGlobalWildcard() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withAllowedUpstreamHosts(List.of("*")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);

            var result = validator.validate(createServiceBuilder().build());

            assertInstanceOf(ValidationResult.Invalid.class, result);
        }

        @Test
        @DisplayName("Should accept an exact host case-insensitively")
        void shouldAcceptExactHost() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withAllowedUpstreamHosts(List.of("EXAMPLE.COM.")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);

            var result = validator.validate(
                    createServiceBuilder().baseUrl("https://example.com").build());

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should allow subdomains without allowing the parent domain")
        void shouldMatchOnlySubdomains() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withAllowedUpstreamHosts(List.of("*.example.com")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);
            var subdomain =
                    createServiceBuilder().baseUrl("https://api.example.com").build();
            var parent = createServiceBuilder().baseUrl("https://example.com").build();

            assertInstanceOf(ValidationResult.Valid.class, validator.validate(subdomain));
            assertInstanceOf(ValidationResult.Invalid.class, validator.validate(parent));
        }

        @Test
        @DisplayName("Should reject an allowlisted private address when private upstreams are disabled")
        void shouldRejectPrivateAddressWhenDisabled() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withUpstreamPolicy(false, List.of("10.0.0.5")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().baseUrl("http://10.0.0.5").build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            assertEquals(400, ((ValidationResult.Invalid) result).suggestedStatusCode());
        }

        @Test
        @DisplayName("Should accept an allowlisted private address when private upstreams are enabled")
        void shouldAcceptPrivateAddressWhenEnabled() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withUpstreamPolicy(true, List.of("10.0.0.5")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().baseUrl("http://10.0.0.5").build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should reject loopback even when private upstreams are enabled")
        void shouldAlwaysRejectLoopback() {
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.withUpstreamPolicy(true, List.of("127.0.0.1")),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().baseUrl("http://127.0.0.1").build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
        }
    }

    @Nested
    @DisplayName("Public Default Visibility")
    class PublicDefaultVisibilityTests {

        @Test
        @DisplayName("Should reject PUBLIC defaultVisibility when disabled by policy")
        void shouldRejectPublicDefaultVisibilityWhenDisabled() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.withPublicVisibility(false);
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(403, invalid.suggestedStatusCode());
        }

        @Test
        @DisplayName("Should accept PUBLIC defaultVisibility when enabled by policy")
        void shouldAcceptPublicDefaultVisibilityWhenEnabled() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.permissive();
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept PRIVATE defaultVisibility regardless of policy")
        void shouldAcceptPrivateDefaultVisibilityRegardlessOfPolicy() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.withPublicVisibility(false);
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept null defaultVisibility (defaults to PRIVATE)")
        void shouldAcceptNullDefaultVisibility() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.withPublicVisibility(false);
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }
    }

    @Nested
    @DisplayName("Visibility Rules Validation")
    class VisibilityRulesTests {

        @Test
        @DisplayName("VisibilityRule constructor should reject null pattern")
        void visibilityRuleShouldRejectNullPattern() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new VisibilityRule(null, Set.of(), EndpointVisibility.PUBLIC));
        }

        @Test
        @DisplayName("VisibilityRule constructor should reject blank pattern")
        void visibilityRuleShouldRejectBlankPattern() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new VisibilityRule("   ", Set.of(), EndpointVisibility.PUBLIC));
        }

        @Test
        @DisplayName("Should accept visibility rule with valid pattern")
        void shouldAcceptVisibilityRuleWithValidPattern() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.permissive();
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var rule = new VisibilityRule("/api/**", Set.of(), EndpointVisibility.PUBLIC);
            var service = createServiceBuilder().visibilityRules(List.of(rule)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept empty visibility rules")
        void shouldAcceptEmptyVisibilityRules() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.permissive();
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().visibilityRules(List.of()).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept multiple valid visibility rules")
        void shouldAcceptMultipleValidVisibilityRules() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.permissive();
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var rule1 = new VisibilityRule("/api/**", Set.of("GET"), EndpointVisibility.PUBLIC);
            var rule2 = new VisibilityRule("/internal/**", Set.of(), EndpointVisibility.PRIVATE);
            var service = createServiceBuilder()
                    .visibilityRules(List.of(rule1, rule2))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept visibility rule with factory methods")
        void shouldAcceptVisibilityRuleWithFactoryMethods() {
            GatewaySecurityConfig config = TestGatewaySecurityConfig.permissive();
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var rule = VisibilityRule.publicRule("/api/**");
            var service = createServiceBuilder().visibilityRules(List.of(rule)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }
    }

    @Nested
    @DisplayName("Combined Validation")
    class CombinedValidationTests {

        @Test
        @DisplayName("Should validate public visibility policy before other checks")
        void shouldValidatePublicVisibilityPolicyFirst() {
            GatewaySecurityConfig config =
                    TestGatewaySecurityConfig.withPublicVisibility(false); // Disables PUBLIC default
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var rule = VisibilityRule.publicRule("/api/**");
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .visibilityRules(List.of(rule))
                    .build();

            var result = validator.validate(service);

            // Should fail on PUBLIC default (403)
            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(403, invalid.suggestedStatusCode());
        }

        @Test
        @DisplayName("Should pass with PRIVATE default and PUBLIC visibility rules")
        void shouldPassWithPrivateDefaultAndPublicRules() {
            GatewaySecurityConfig config =
                    TestGatewaySecurityConfig.withPublicVisibility(false); // Disables PUBLIC default
            var validator = new ServiceRegistrationValidator(
                    config, PERMISSIVE_RATE_LIMIT_CONFIG, PERMISSIVE_RESILIENCY_CONFIG);
            var rule = VisibilityRule.publicRule("/api/**");
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .visibilityRules(List.of(rule))
                    .build();

            var result = validator.validate(service);

            // PRIVATE default is allowed, and specific paths can still be PUBLIC
            assertInstanceOf(ValidationResult.Valid.class, result);
        }
    }

    @Nested
    @DisplayName("Rate Limit Window Seconds Validation")
    class WindowSecondsValidationTests {

        @Test
        @DisplayName("Should reject when HTTP windowSeconds exceeds platform max")
        void shouldRejectWhenHttpWindowSecondsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(100, 600))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("600"));
            assertTrue(invalid.reason().contains("300"));
        }

        @Test
        @DisplayName("Should reject when WebSocket connection windowSeconds exceeds platform max")
        void shouldRejectWhenWebSocketConnectionWindowSecondsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(RateLimitValues.of(10, 600), null);
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket connection"));
            assertTrue(invalid.reason().contains("600"));
            assertTrue(invalid.reason().contains("300"));
        }

        @Test
        @DisplayName("Should reject when WebSocket message windowSeconds exceeds platform max")
        void shouldRejectWhenWebSocketMessageWindowSecondsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(null, RateLimitValues.of(100, 600));
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket message"));
            assertTrue(invalid.reason().contains("600"));
            assertTrue(invalid.reason().contains("300"));
        }

        @Test
        @DisplayName("Should accept WebSocket config with no windowSeconds set")
        void shouldAcceptWebSocketConfigWithNoWindowSeconds() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var connectionConfig = new RateLimitValues(Optional.of(50L), Optional.empty(), Optional.empty());
            final var wsConfig = new ServiceWebSocketRateLimitConfig(Optional.of(connectionConfig), Optional.empty());
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when windowSeconds equals platform max")
        void shouldAcceptWhenWindowSecondsEqualsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(100, 300))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when windowSeconds is under platform max")
        void shouldAcceptWhenWindowSecondsUnderPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(100, 60))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when no rate limit config is set")
        void shouldAcceptWhenNoRateLimitConfig() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Error message should specify the platform maximum")
        void errorMessageShouldSpecifyPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxWindowSeconds(3600);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(100, 86400))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals("HTTP windowSeconds 86400 exceeds the platform maximum of 3600 seconds.", invalid.reason());
        }
    }

    @Nested
    @DisplayName("Requests Per Window Validation")
    class RequestsPerWindowValidationTests {

        @Test
        @DisplayName("Should reject when HTTP requestsPerWindow exceeds platform max")
        void shouldRejectWhenHttpRequestsPerWindowExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(500, 60))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("500"));
            assertTrue(invalid.reason().contains("100"));
        }

        @Test
        @DisplayName("Should reject when HTTP burstCapacity exceeds platform max")
        void shouldRejectWhenHttpBurstCapacityExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(50, 60, 200))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("burstCapacity"));
            assertTrue(invalid.reason().contains("200"));
        }

        @Test
        @DisplayName("Should accept when requestsPerWindow equals platform max")
        void shouldAcceptWhenRequestsPerWindowEqualsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(100, 60))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when requestsPerWindow is under platform max")
        void shouldAcceptWhenRequestsPerWindowUnderPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(50, 60))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when no rate limit config is set")
        void shouldAcceptWhenNoRateLimitConfig() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder().build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should reject when WebSocket connection requestsPerWindow exceeds platform max")
        void shouldRejectWhenWebSocketConnectionRequestsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(RateLimitValues.of(500, 60), null);
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket connection"));
            assertTrue(invalid.reason().contains("500"));
        }

        @Test
        @DisplayName("Should reject when WebSocket message requestsPerWindow exceeds platform max")
        void shouldRejectWhenWebSocketMessageRequestsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(null, RateLimitValues.of(500, 60));
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket message"));
            assertTrue(invalid.reason().contains("500"));
        }

        @Test
        @DisplayName("Should reject when WebSocket connection burstCapacity exceeds platform max")
        void shouldRejectWhenWebSocketConnectionBurstCapacityExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var connValues = RateLimitValues.of(50, 60, 500);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(connValues, null);
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket connection burstCapacity"));
            assertTrue(invalid.reason().contains("500"));
        }

        @Test
        @DisplayName("Should reject when WebSocket message burstCapacity exceeds platform max")
        void shouldRejectWhenWebSocketMessageBurstCapacityExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            final var msgValues = RateLimitValues.of(50, 60, 500);
            final var wsConfig = ServiceWebSocketRateLimitConfig.of(null, msgValues);
            var service = createServiceBuilder()
                    .rateLimitConfig(new ServiceRateLimitConfig(
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(wsConfig)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("WebSocket message burstCapacity"));
            assertTrue(invalid.reason().contains("500"));
        }

        @Test
        @DisplayName("Error message should specify the platform maximum")
        void errorMessageShouldSpecifyPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(1000);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var service = createServiceBuilder()
                    .rateLimitConfig(ServiceRateLimitConfig.of(5000, 60))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals("HTTP requestsPerWindow 5000 exceeds the platform maximum of 1000.", invalid.reason());
        }
    }

    @Nested
    @DisplayName("Endpoint Rate Limit Validation")
    class EndpointRateLimitValidationTests {

        @Test
        @DisplayName("Should reject when endpoint requestsPerWindow exceeds platform max")
        void shouldRejectWhenEndpointRequestsPerWindowExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.of(EndpointRateLimitConfig.of(500, 60)));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("/api/test"));
            assertTrue(invalid.reason().contains("500"));
        }

        @Test
        @DisplayName("Should reject when endpoint burstCapacity exceeds platform max")
        void shouldRejectWhenEndpointBurstCapacityExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.of(EndpointRateLimitConfig.of(50, 60, 200)));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("burstCapacity"));
            assertTrue(invalid.reason().contains("/api/test"));
        }

        @Test
        @DisplayName("Should reject when endpoint windowSeconds exceeds platform max")
        void shouldRejectWhenEndpointWindowSecondsExceedsPlatformMax() {
            var rateLimitConfig = TestRateLimitingConfig.withMaximums(Long.MAX_VALUE, 300);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.of(EndpointRateLimitConfig.of(100, 600)));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("/api/test"));
            assertTrue(invalid.reason().contains("600"));
            assertTrue(invalid.reason().contains("300"));
        }

        @Test
        @DisplayName("Should accept endpoint with valid rate limits")
        void shouldAcceptEndpointWithValidRateLimits() {
            var rateLimitConfig = TestRateLimitingConfig.withMaximums(1000, 3600);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.of(EndpointRateLimitConfig.of(100, 60, 50)));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept endpoint without rate limit config")
        void shouldAcceptEndpointWithoutRateLimitConfig() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = EndpointConfig.publicEndpoint("/api/test", Set.of("GET"));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Error message should include endpoint path")
        void errorMessageShouldIncludeEndpointPath() {
            var rateLimitConfig = TestRateLimitingConfig.withMaxRequestsPerWindow(100);
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), rateLimitConfig, PERMISSIVE_RESILIENCY_CONFIG);
            var endpoint = new EndpointConfig(
                    "/api/users",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.of(EndpointRateLimitConfig.of(500, 60)));
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(
                    "Endpoint '/api/users' requestsPerWindow 500 exceeds the platform maximum of 100.",
                    invalid.reason());
        }
    }

    @Nested
    @DisplayName("Access Control Policy")
    class AccessControlPolicyTests {

        @Test
        @DisplayName("Should accept a service IP range contained by the global boundary")
        void shouldAcceptNarrowerServiceRange() {
            var validator = createAccessValidator(List.of("10.0.0.0/8"));
            var service = createServiceBuilder()
                    .accessConfig(new ServiceAccessConfig(
                            Optional.of(List.of("10.20.0.0/16")), Optional.empty(), Optional.empty()))
                    .build();

            assertInstanceOf(ValidationResult.Valid.class, validator.validate(service));
        }

        @Test
        @DisplayName("Should reject a service IP range that broadens the global boundary")
        void shouldRejectBroaderServiceRange() {
            var validator = createAccessValidator(List.of("10.0.0.0/8"));
            var service = createServiceBuilder()
                    .accessConfig(new ServiceAccessConfig(
                            Optional.of(List.of("0.0.0.0/0")), Optional.empty(), Optional.empty()))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            assertTrue(((ValidationResult.Invalid) result).reason().contains("global allowed IP boundary"));
        }

        @Test
        @DisplayName("Should reject invalid service IP ranges")
        void shouldRejectInvalidServiceRange() {
            var validator = createAccessValidator(List.of("10.0.0.0/8"));
            var service = createServiceBuilder()
                    .accessConfig(new ServiceAccessConfig(
                            Optional.of(List.of("10.0.0.0/33")), Optional.empty(), Optional.empty()))
                    .build();

            assertInstanceOf(ValidationResult.Invalid.class, validator.validate(service));
        }

        @Test
        @DisplayName("Should reject domain-based caller policies")
        void shouldRejectDomainPolicy() {
            var validator = createAccessValidator(List.of("10.0.0.0/8"));
            var service = createServiceBuilder()
                    .accessConfig(new ServiceAccessConfig(
                            Optional.empty(), Optional.of(List.of("internal.example")), Optional.empty()))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            assertTrue(((ValidationResult.Invalid) result).reason().contains("Domain-based caller access control"));
        }

        private ServiceRegistrationValidator createAccessValidator(List<String> globalAllowedIps) {
            AccessControlConfig accessConfig = new AccessControlConfig() {
                @Override
                public Optional<List<String>> allowedIps() {
                    return Optional.of(globalAllowedIps);
                }

                @Override
                public Optional<List<String>> allowedDomains() {
                    return Optional.empty();
                }

                @Override
                public Optional<List<String>> allowedSubdomains() {
                    return Optional.empty();
                }
            };
            return new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(),
                    PERMISSIVE_RATE_LIMIT_CONFIG,
                    PERMISSIVE_RESILIENCY_CONFIG,
                    accessConfig);
        }
    }

    @Nested
    @DisplayName("Timeout Validation")
    class TimeoutValidationTests {

        @Test
        @DisplayName("Should reject when service requestTimeout exceeds platform max")
        void shouldRejectWhenServiceTimeoutExceedsPlatformMax() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofMinutes(2));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var service = createServiceBuilder()
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofMinutes(3)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("Service"));
            assertTrue(invalid.reason().contains("PT3M"));
            assertTrue(invalid.reason().contains("PT2M"));
        }

        @Test
        @DisplayName("Should accept when service requestTimeout equals platform max")
        void shouldAcceptWhenServiceTimeoutEqualsPlatformMax() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofMinutes(2));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var service = createServiceBuilder()
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofMinutes(2)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when service requestTimeout is under platform max")
        void shouldAcceptWhenServiceTimeoutUnderPlatformMax() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofMinutes(2));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var service = createServiceBuilder()
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofSeconds(30)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept when no timeout config is set")
        void shouldAcceptWhenNoTimeoutConfig() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofSeconds(30));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var service = createServiceBuilder().build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should reject when endpoint requestTimeout exceeds platform max")
        void shouldRejectWhenEndpointTimeoutExceedsPlatformMax() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofMinutes(2));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var endpoint = new EndpointConfig(
                    "/api/slow",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(EndpointTimeoutConfig.of(Duration.ofMinutes(5))),
                    Optional.empty());
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(400, invalid.suggestedStatusCode());
            assertTrue(invalid.reason().contains("/api/slow"));
            assertTrue(invalid.reason().contains("PT5M"));
            assertTrue(invalid.reason().contains("PT2M"));
        }

        @Test
        @DisplayName("Should accept when endpoint requestTimeout is under platform max")
        void shouldAcceptWhenEndpointTimeoutUnderPlatformMax() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofMinutes(2));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var endpoint = new EndpointConfig(
                    "/api/slow",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(EndpointTimeoutConfig.of(Duration.ofSeconds(90))),
                    Optional.empty());
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Error message should include duration values for service timeout")
        void errorMessageShouldIncludeDurationValues() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofSeconds(30));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var service = createServiceBuilder()
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofSeconds(60)))
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals("Service requestTimeout PT1M exceeds the platform maximum of PT30S.", invalid.reason());
        }

        @Test
        @DisplayName("Error message should include endpoint path for endpoint timeout")
        void errorMessageShouldIncludeEndpointPath() {
            var resiliencyConfig = TestResiliencyConfig.withMaxRequestTimeout(Duration.ofSeconds(30));
            var validator = new ServiceRegistrationValidator(
                    TestGatewaySecurityConfig.permissive(), PERMISSIVE_RATE_LIMIT_CONFIG, resiliencyConfig);
            var endpoint = new EndpointConfig(
                    "/api/reports",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    aussie.core.model.routing.EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(EndpointTimeoutConfig.of(Duration.ofSeconds(60))),
                    Optional.empty());
            var service = createServiceBuilder().endpoints(List.of(endpoint)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals(
                    "Endpoint '/api/reports' requestTimeout PT1M exceeds the platform maximum of PT30S.",
                    invalid.reason());
        }
    }
}
