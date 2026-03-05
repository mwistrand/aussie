package aussie.core.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;

@DisplayName("VisibilityResolver")
class VisibilityResolverTest {

    private VisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new VisibilityResolver(new GlobPatternMatcher());
    }

    private ServiceRegistration serviceWith(EndpointVisibility defaultVisibility, List<VisibilityRule> rules) {
        return ServiceRegistration.builder("test-service")
                .displayName("Test Service")
                .baseUrl(URI.create("http://localhost:8080"))
                .defaultVisibility(defaultVisibility)
                .visibilityRules(rules)
                .build();
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("should return default visibility when no rules match")
        void shouldReturnDefaultWhenNoRulesMatch() {
            var service = serviceWith(EndpointVisibility.PRIVATE, List.of());

            var result = resolver.resolve("/api/users", "GET", service);

            assertEquals(EndpointVisibility.PRIVATE, result);
        }

        @Test
        @DisplayName("should return PUBLIC default when no rules match")
        void shouldReturnPublicDefaultWhenNoRulesMatch() {
            var service = serviceWith(EndpointVisibility.PUBLIC, List.of());

            var result = resolver.resolve("/api/users", "GET", service);

            assertEquals(EndpointVisibility.PUBLIC, result);
        }

        @Test
        @DisplayName("should return first matching rule's visibility")
        void shouldReturnFirstMatchingRuleVisibility() {
            var rules = List.of(VisibilityRule.publicRule("/api/health"), VisibilityRule.privateRule("/api/**"));
            var service = serviceWith(EndpointVisibility.PUBLIC, rules);

            var result = resolver.resolve("/api/health", "GET", service);

            assertEquals(EndpointVisibility.PUBLIC, result);
        }

        @Test
        @DisplayName("should use first match when multiple rules match")
        void shouldUseFirstMatchWhenMultipleRulesMatch() {
            var rules = List.of(VisibilityRule.privateRule("/api/**"), VisibilityRule.publicRule("/api/users"));
            var service = serviceWith(EndpointVisibility.PUBLIC, rules);

            // Both rules match /api/users, but the first one wins
            var result = resolver.resolve("/api/users", "GET", service);

            assertEquals(EndpointVisibility.PRIVATE, result);
        }

        @Test
        @DisplayName("should skip rules that don't match method")
        void shouldSkipRulesThatDontMatchMethod() {
            var rules = List.of(
                    VisibilityRule.publicRule("/api/users", Set.of("POST")), VisibilityRule.privateRule("/api/users"));
            var service = serviceWith(EndpointVisibility.PUBLIC, rules);

            // GET doesn't match the POST-only public rule, so private rule wins
            var result = resolver.resolve("/api/users", "GET", service);

            assertEquals(EndpointVisibility.PRIVATE, result);
        }

        @Test
        @DisplayName("should match rules that apply to all methods")
        void shouldMatchRulesThatApplyToAllMethods() {
            var rules = List.of(VisibilityRule.publicRule("/api/health"));
            var service = serviceWith(EndpointVisibility.PRIVATE, rules);

            // Rule with empty methods set applies to all methods
            assertEquals(EndpointVisibility.PUBLIC, resolver.resolve("/api/health", "GET", service));
            assertEquals(EndpointVisibility.PUBLIC, resolver.resolve("/api/health", "POST", service));
        }

        @Test
        @DisplayName("should fall back to default when no rule patterns match")
        void shouldFallBackToDefaultWhenNoPatternsMatch() {
            var rules = List.of(VisibilityRule.publicRule("/api/health"));
            var service = serviceWith(EndpointVisibility.PRIVATE, rules);

            var result = resolver.resolve("/api/users", "GET", service);

            assertEquals(EndpointVisibility.PRIVATE, result);
        }

        @Test
        @DisplayName("should match method-specific rules correctly")
        void shouldMatchMethodSpecificRulesCorrectly() {
            var rules = List.of(VisibilityRule.publicRule("/api/users", Set.of("GET")));
            var service = serviceWith(EndpointVisibility.PRIVATE, rules);

            assertEquals(EndpointVisibility.PUBLIC, resolver.resolve("/api/users", "GET", service));
            assertEquals(EndpointVisibility.PRIVATE, resolver.resolve("/api/users", "POST", service));
        }
    }
}
