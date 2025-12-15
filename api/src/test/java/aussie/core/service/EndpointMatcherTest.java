package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.*;

@DisplayName("EndpointMatcher Tests")
class EndpointMatcherTest {

    private EndpointMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new EndpointMatcher(new GlobPatternMatcher());
    }

    private ServiceRegistration createService(List<EndpointConfig> endpoints) {
        return ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .endpoints(endpoints)
                .build();
    }

    @Nested
    @DisplayName("Path Matching")
    class PathMatchingTests {

        @Test
        @DisplayName("Should match exact path")
        void shouldMatchExactPath() {
            var endpoint =
                    new EndpointConfig("/api/health", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            var result = matcher.match("/api/health", "GET", service);

            assertTrue(result.isPresent());
            assertEquals("/api/health", result.get().path());
        }

        @Test
        @DisplayName("Should match glob pattern with single wildcard")
        void shouldMatchGlobPatternWithSingleWildcard() {
            var endpoint =
                    new EndpointConfig("/api/users/*", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            var result = matcher.match("/api/users/123", "GET", service);

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("Should match glob pattern with double wildcard")
        void shouldMatchGlobPatternWithDoubleWildcard() {
            var endpoint = new EndpointConfig("/api/**", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            assertTrue(matcher.match("/api/users", "GET", service).isPresent());
            assertTrue(matcher.match("/api/users/123", "GET", service).isPresent());
            assertTrue(matcher.match("/api/users/123/profile", "GET", service).isPresent());
        }

        @Test
        @DisplayName("Should return empty when path does not match")
        void shouldReturnEmptyWhenPathDoesNotMatch() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            var result = matcher.match("/api/products", "GET", service);

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("Method Matching")
    class MethodMatchingTests {

        @Test
        @DisplayName("Should match when method is in allowed set")
        void shouldMatchWhenMethodIsInAllowedSet() {
            var endpoint = new EndpointConfig(
                    "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            assertTrue(matcher.match("/api/users", "GET", service).isPresent());
            assertTrue(matcher.match("/api/users", "POST", service).isPresent());
        }

        @Test
        @DisplayName("Should not match when method is not in allowed set")
        void shouldNotMatchWhenMethodIsNotInAllowedSet() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            assertFalse(matcher.match("/api/users", "POST", service).isPresent());
            assertFalse(matcher.match("/api/users", "DELETE", service).isPresent());
        }

        @Test
        @DisplayName("Should match wildcard method")
        void shouldMatchWildcardMethod() {
            var endpoint = new EndpointConfig("/api/users", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            assertTrue(matcher.match("/api/users", "GET", service).isPresent());
            assertTrue(matcher.match("/api/users", "POST", service).isPresent());
            assertTrue(matcher.match("/api/users", "DELETE", service).isPresent());
        }

        @Test
        @DisplayName("Should match method case-insensitively")
        void shouldMatchMethodCaseInsensitively() {
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = createService(List.of(endpoint));

            assertTrue(matcher.match("/api/users", "get", service).isPresent());
            assertTrue(matcher.match("/api/users", "Get", service).isPresent());
            assertTrue(matcher.match("/api/users", "GET", service).isPresent());
        }
    }

    @Nested
    @DisplayName("Endpoint Order")
    class EndpointOrderTests {

        @Test
        @DisplayName("Should return first matching endpoint (order matters)")
        void shouldReturnFirstMatchingEndpoint() {
            var specificEndpoint = new EndpointConfig(
                    "/api/users/admin", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty(), true);
            var wildcardEndpoint = new EndpointConfig(
                    "/api/users/**", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
            var service = createService(List.of(specificEndpoint, wildcardEndpoint));

            var result = matcher.match("/api/users/admin", "GET", service);

            assertTrue(result.isPresent());
            assertEquals("/api/users/admin", result.get().path());
            assertTrue(result.get().authRequired());
        }

        @Test
        @DisplayName("Should match wildcard when specific path not first")
        void shouldMatchWildcardWhenSpecificPathNotFirst() {
            var wildcardEndpoint = new EndpointConfig(
                    "/api/users/**", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
            var specificEndpoint = new EndpointConfig(
                    "/api/users/admin", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty(), true);
            var service = createService(List.of(wildcardEndpoint, specificEndpoint));

            // Wildcard comes first, so it should match
            var result = matcher.match("/api/users/admin", "GET", service);

            assertTrue(result.isPresent());
            assertEquals("/api/users/**", result.get().path());
            assertFalse(result.get().authRequired());
        }
    }

    @Nested
    @DisplayName("Empty Endpoints")
    class EmptyEndpointsTests {

        @Test
        @DisplayName("Should return empty when no endpoints configured")
        void shouldReturnEmptyWhenNoEndpointsConfigured() {
            var service = createService(List.of());

            var result = matcher.match("/api/users", "GET", service);

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("AuthRequired Preservation")
    class AuthRequiredPreservationTests {

        @Test
        @DisplayName("Should preserve authRequired=true from matched endpoint")
        void shouldPreserveAuthRequiredTrue() {
            var endpoint = new EndpointConfig(
                    "/api/protected", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), true);
            var service = createService(List.of(endpoint));

            var result = matcher.match("/api/protected", "GET", service);

            assertTrue(result.isPresent());
            assertTrue(result.get().authRequired());
        }

        @Test
        @DisplayName("Should preserve authRequired=false from matched endpoint")
        void shouldPreserveAuthRequiredFalse() {
            var endpoint = new EndpointConfig(
                    "/api/public", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
            var service = createService(List.of(endpoint));

            var result = matcher.match("/api/public", "GET", service);

            assertTrue(result.isPresent());
            assertFalse(result.get().authRequired());
        }
    }
}
