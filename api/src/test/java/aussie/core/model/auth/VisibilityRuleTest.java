package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.routing.EndpointVisibility;

@DisplayName("VisibilityRule")
class VisibilityRuleTest {

    @Test
    @DisplayName("Should throw on null pattern")
    void shouldThrowOnNullPattern() {
        assertThrows(
                IllegalArgumentException.class, () -> new VisibilityRule(null, Set.of(), EndpointVisibility.PUBLIC));
    }

    @Test
    @DisplayName("Should throw on null visibility")
    void shouldThrowOnNullVisibility() {
        assertThrows(IllegalArgumentException.class, () -> new VisibilityRule("/api/**", Set.of(), null));
    }

    @Test
    @DisplayName("Should default null methods to empty set")
    void shouldDefaultNullMethods() {
        var rule = new VisibilityRule("/api/**", null, EndpointVisibility.PUBLIC);
        assertTrue(rule.methods().isEmpty());
    }

    @Test
    @DisplayName("privateRule with methods should create PRIVATE rule")
    void privateRuleWithMethods() {
        var rule = VisibilityRule.privateRule("/admin/**", Set.of("POST", "DELETE"));
        assertEquals(EndpointVisibility.PRIVATE, rule.visibility());
        assertEquals(Set.of("POST", "DELETE"), rule.methods());
    }

    @Test
    @DisplayName("appliesToAllMethods should return true when methods is empty")
    void appliesToAllMethods() {
        var rule = VisibilityRule.publicRule("/api/**");
        assertTrue(rule.appliesToAllMethods());
        assertTrue(rule.appliesToMethod("GET"));
    }

    @Test
    @DisplayName("appliesToMethod should match specific methods case-insensitively")
    void appliesToSpecificMethod() {
        var rule = VisibilityRule.publicRule("/api/**", Set.of("GET", "POST"));
        assertTrue(rule.appliesToMethod("get"));
        assertTrue(rule.appliesToMethod("POST"));
        assertFalse(rule.appliesToMethod("DELETE"));
    }
}
