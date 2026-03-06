package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceAccessConfig")
class ServiceAccessConfigTest {

    @Test
    @DisplayName("Should default null fields to empty optionals")
    void shouldDefaultNullFields() {
        var config = new ServiceAccessConfig(null, null, null);
        assertEquals(Optional.empty(), config.allowedIps());
        assertEquals(Optional.empty(), config.allowedDomains());
        assertEquals(Optional.empty(), config.allowedSubdomains());
    }

    @Test
    @DisplayName("empty() should have no restrictions")
    void emptyShouldHaveNoRestrictions() {
        assertFalse(ServiceAccessConfig.empty().hasRestrictions());
    }

    @Test
    @DisplayName("hasRestrictions should return true when allowedIps is set")
    void shouldDetectIpRestrictions() {
        var config = new ServiceAccessConfig(Optional.of(List.of("10.0.0.1")), Optional.empty(), Optional.empty());
        assertTrue(config.hasRestrictions());
    }

    @Test
    @DisplayName("hasRestrictions should return true when allowedDomains is set")
    void shouldDetectDomainRestrictions() {
        var config = new ServiceAccessConfig(Optional.empty(), Optional.of(List.of("example.com")), Optional.empty());
        assertTrue(config.hasRestrictions());
    }

    @Test
    @DisplayName("hasRestrictions should return true when allowedSubdomains is set")
    void shouldDetectSubdomainRestrictions() {
        var config = new ServiceAccessConfig(Optional.empty(), Optional.empty(), Optional.of(List.of(".example.com")));
        assertTrue(config.hasRestrictions());
    }
}
