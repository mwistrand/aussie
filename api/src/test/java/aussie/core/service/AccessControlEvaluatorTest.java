package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.AccessControlConfig;
import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RouteLookupResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceAccessConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.SourceIdentifier;

@DisplayName("AccessControlEvaluator")
class AccessControlEvaluatorTest {

    private AccessControlEvaluator evaluator;
    private TestAccessControlConfig config;

    @BeforeEach
    void setUp() {
        config = new TestAccessControlConfig();
        evaluator = new AccessControlEvaluator(config);
    }

    @Nested
    @DisplayName("Public Endpoints")
    class PublicEndpointTests {

        @Test
        @DisplayName("Should always allow access to public endpoints")
        void shouldAllowPublicEndpoints() {
            var source = SourceIdentifier.of("192.168.1.1");
            var route = createPublicRoute("/api/public");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should allow public endpoints even without matching IP")
        void shouldAllowPublicEndpointsRegardlessOfIp() {
            var source = SourceIdentifier.of("203.0.113.50"); // External IP
            var route = createPublicRoute("/api/public");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("Private Endpoints - IP Matching")
    class PrivateEndpointIpTests {

        @Test
        @DisplayName("Should allow private endpoint for exact IP match")
        void shouldAllowExactIpMatch() {
            config.setAllowedIps(List.of("192.168.1.100"));
            var source = SourceIdentifier.of("192.168.1.100");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should deny private endpoint for non-matching IP")
        void shouldDenyNonMatchingIp() {
            config.setAllowedIps(List.of("192.168.1.100"));
            var source = SourceIdentifier.of("192.168.1.101");
            var route = createPrivateRoute("/api/private");

            assertFalse(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should allow private endpoint for CIDR match")
        void shouldAllowCidrMatch() {
            config.setAllowedIps(List.of("10.0.0.0/8"));
            var source = SourceIdentifier.of("10.1.2.3");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should deny private endpoint for IP outside CIDR range")
        void shouldDenyIpOutsideCidrRange() {
            config.setAllowedIps(List.of("10.0.0.0/8"));
            var source = SourceIdentifier.of("192.168.1.1");
            var route = createPrivateRoute("/api/private");

            assertFalse(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should handle /24 CIDR notation")
        void shouldHandle24CidrNotation() {
            config.setAllowedIps(List.of("192.168.1.0/24"));
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(SourceIdentifier.of("192.168.1.1"), route, Optional.empty()));
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("192.168.1.254"), route, Optional.empty()));
            assertFalse(evaluator.isAllowed(SourceIdentifier.of("192.168.2.1"), route, Optional.empty()));
        }

        @Test
        @DisplayName("Should handle /16 CIDR notation")
        void shouldHandle16CidrNotation() {
            config.setAllowedIps(List.of("172.16.0.0/16"));
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(SourceIdentifier.of("172.16.0.1"), route, Optional.empty()));
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("172.16.255.255"), route, Optional.empty()));
            assertFalse(evaluator.isAllowed(SourceIdentifier.of("172.17.0.1"), route, Optional.empty()));
        }

        @Test
        @DisplayName("Should allow localhost")
        void shouldAllowLocalhost() {
            config.setAllowedIps(List.of("127.0.0.1"));
            var source = SourceIdentifier.of("127.0.0.1");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should support multiple IP patterns")
        void shouldSupportMultipleIpPatterns() {
            config.setAllowedIps(List.of("192.168.1.0/24", "10.0.0.0/8", "172.16.0.1"));
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(SourceIdentifier.of("192.168.1.50"), route, Optional.empty()));
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("10.255.0.1"), route, Optional.empty()));
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("172.16.0.1"), route, Optional.empty()));
            assertFalse(evaluator.isAllowed(SourceIdentifier.of("172.16.0.2"), route, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("Private Endpoints - Domain Matching")
    class PrivateEndpointDomainTests {

        @Test
        @DisplayName("Should allow private endpoint for exact domain match")
        void shouldAllowExactDomainMatch() {
            config.setAllowedDomains(List.of("internal.example.com"));
            var source = SourceIdentifier.of("192.168.1.1", "internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should deny private endpoint for non-matching domain")
        void shouldDenyNonMatchingDomain() {
            config.setAllowedDomains(List.of("internal.example.com"));
            var source = SourceIdentifier.of("192.168.1.1", "external.example.com");
            var route = createPrivateRoute("/api/private");

            assertFalse(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should match domains case-insensitively")
        void shouldMatchDomainsCaseInsensitively() {
            config.setAllowedDomains(List.of("Internal.Example.COM"));
            var source = SourceIdentifier.of("192.168.1.1", "internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("Private Endpoints - Subdomain Matching")
    class PrivateEndpointSubdomainTests {

        @Test
        @DisplayName("Should allow private endpoint for wildcard subdomain match")
        void shouldAllowWildcardSubdomainMatch() {
            config.setAllowedSubdomains(List.of("*.internal.example.com"));
            var source = SourceIdentifier.of("192.168.1.1", "api.internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should allow nested subdomains with wildcard")
        void shouldAllowNestedSubdomains() {
            config.setAllowedSubdomains(List.of("*.internal.example.com"));
            var source = SourceIdentifier.of("192.168.1.1", "deep.nested.internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should not match root domain with wildcard subdomain pattern")
        void shouldNotMatchRootDomainWithWildcard() {
            config.setAllowedSubdomains(List.of("*.internal.example.com"));
            var source = SourceIdentifier.of("192.168.1.1", "internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertFalse(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should match subdomains case-insensitively")
        void shouldMatchSubdomainsCaseInsensitively() {
            config.setAllowedSubdomains(List.of("*.Internal.Example.COM"));
            var source = SourceIdentifier.of("192.168.1.1", "api.internal.example.com");
            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(source, route, Optional.empty()));
        }
    }

    @Nested
    @DisplayName("Service-Specific Access Config")
    class ServiceSpecificAccessTests {

        @Test
        @DisplayName("Should use service-specific config when provided")
        void shouldUseServiceSpecificConfig() {
            // Global config allows 10.0.0.0/8
            config.setAllowedIps(List.of("10.0.0.0/8"));

            // Service config only allows 172.16.0.0/12
            var serviceConfig =
                    new ServiceAccessConfig(Optional.of(List.of("172.16.0.0/12")), Optional.empty(), Optional.empty());

            var route = createPrivateRoute("/api/private");

            // Should deny 10.x.x.x (allowed by global but not by service)
            assertFalse(evaluator.isAllowed(SourceIdentifier.of("10.0.0.1"), route, Optional.of(serviceConfig)));

            // Should allow 172.16.x.x (allowed by service)
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("172.16.1.1"), route, Optional.of(serviceConfig)));
        }

        @Test
        @DisplayName("Should fall back to global config when service has no restrictions")
        void shouldFallBackToGlobalConfig() {
            config.setAllowedIps(List.of("10.0.0.0/8"));

            // Service config with no restrictions
            var serviceConfig = new ServiceAccessConfig(Optional.empty(), Optional.empty(), Optional.empty());

            var route = createPrivateRoute("/api/private");

            assertTrue(evaluator.isAllowed(SourceIdentifier.of("10.0.0.1"), route, Optional.of(serviceConfig)));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should deny when no IP config and source has no host")
        void shouldDenyWhenNoConfigMatches() {
            // No config set
            var source = SourceIdentifier.of("192.168.1.1");
            var route = createPrivateRoute("/api/private");

            assertFalse(evaluator.isAllowed(source, route, Optional.empty()));
        }

        @Test
        @DisplayName("Should handle invalid CIDR notation gracefully")
        void shouldHandleInvalidCidrGracefully() {
            config.setAllowedIps(List.of("invalid/cidr", "10.0.0.0/8"));
            var route = createPrivateRoute("/api/private");

            // Should still match valid pattern
            assertTrue(evaluator.isAllowed(SourceIdentifier.of("10.0.0.1"), route, Optional.empty()));
        }
    }

    // Helper methods to create RouteMatch instances for testing
    private RouteLookupResult createPublicRoute(String path) {
        var service = ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .endpoints(List.of())
                .build();
        var endpoint = new EndpointConfig(path, Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, endpoint, path, Map.of());
    }

    private RouteLookupResult createPrivateRoute(String path) {
        var service = ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .endpoints(List.of())
                .build();
        var endpoint = new EndpointConfig(path, Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty());
        return new RouteMatch(service, endpoint, path, Map.of());
    }

    /**
     * Simple test implementation of AccessControlConfig for unit testing.
     */
    private static class TestAccessControlConfig implements AccessControlConfig {
        private List<String> allowedIps = List.of();
        private List<String> allowedDomains = List.of();
        private List<String> allowedSubdomains = List.of();

        void setAllowedIps(List<String> ips) {
            this.allowedIps = ips;
        }

        void setAllowedDomains(List<String> domains) {
            this.allowedDomains = domains;
        }

        void setAllowedSubdomains(List<String> subdomains) {
            this.allowedSubdomains = subdomains;
        }

        @Override
        public Optional<List<String>> allowedIps() {
            return allowedIps.isEmpty() ? Optional.empty() : Optional.of(allowedIps);
        }

        @Override
        public Optional<List<String>> allowedDomains() {
            return allowedDomains.isEmpty() ? Optional.empty() : Optional.of(allowedDomains);
        }

        @Override
        public Optional<List<String>> allowedSubdomains() {
            return allowedSubdomains.isEmpty() ? Optional.empty() : Optional.of(allowedSubdomains);
        }
    }
}
