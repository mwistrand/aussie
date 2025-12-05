package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.GatewaySecurityConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.ValidationResult;
import aussie.core.model.VisibilityRule;

@DisplayName("ServiceRegistrationValidator")
class ServiceRegistrationValidatorTest {

    private ServiceRegistration.Builder createServiceBuilder() {
        return ServiceRegistration.builder("test-service").baseUrl("http://localhost:8080");
    }

    @Nested
    @DisplayName("Public Default Visibility")
    class PublicDefaultVisibilityTests {

        @Test
        @DisplayName("Should reject PUBLIC defaultVisibility when disabled by policy")
        void shouldRejectPublicDefaultVisibilityWhenDisabled() {
            GatewaySecurityConfig config = () -> false;
            var validator = new ServiceRegistrationValidator(config);
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
            GatewaySecurityConfig config = () -> true;
            var validator = new ServiceRegistrationValidator(config);
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept PRIVATE defaultVisibility regardless of policy")
        void shouldAcceptPrivateDefaultVisibilityRegardlessOfPolicy() {
            GatewaySecurityConfig config = () -> false;
            var validator = new ServiceRegistrationValidator(config);
            var service = createServiceBuilder()
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept null defaultVisibility (defaults to PRIVATE)")
        void shouldAcceptNullDefaultVisibility() {
            GatewaySecurityConfig config = () -> false;
            var validator = new ServiceRegistrationValidator(config);
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
            GatewaySecurityConfig config = () -> true;
            var validator = new ServiceRegistrationValidator(config);
            var rule = new VisibilityRule("/api/**", Set.of(), EndpointVisibility.PUBLIC);
            var service = createServiceBuilder().visibilityRules(List.of(rule)).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept empty visibility rules")
        void shouldAcceptEmptyVisibilityRules() {
            GatewaySecurityConfig config = () -> true;
            var validator = new ServiceRegistrationValidator(config);
            var service = createServiceBuilder().visibilityRules(List.of()).build();

            var result = validator.validate(service);

            assertInstanceOf(ValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("Should accept multiple valid visibility rules")
        void shouldAcceptMultipleValidVisibilityRules() {
            GatewaySecurityConfig config = () -> true;
            var validator = new ServiceRegistrationValidator(config);
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
            GatewaySecurityConfig config = () -> true;
            var validator = new ServiceRegistrationValidator(config);
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
            GatewaySecurityConfig config = () -> false; // Disables PUBLIC default
            var validator = new ServiceRegistrationValidator(config);
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
            GatewaySecurityConfig config = () -> false; // Disables PUBLIC default
            var validator = new ServiceRegistrationValidator(config);
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
}
