package aussie.core.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;

@DisplayName("RouteIndex")
class RouteIndexTest {

    private static EndpointConfig endpoint(String path, String... methods) {
        return new EndpointConfig(path, Set.of(methods), EndpointVisibility.PUBLIC, Optional.empty());
    }

    private static EndpointConfig endpointWithRewrite(String path, String rewrite, String... methods) {
        return new EndpointConfig(path, Set.of(methods), EndpointVisibility.PUBLIC, Optional.of(rewrite));
    }

    private static ServiceRegistration service(List<EndpointConfig> endpoints) {
        return ServiceRegistration.builder("svc")
                .baseUrl(URI.create("http://localhost:8080"))
                .endpoints(endpoints)
                .build();
    }

    @Nested
    @DisplayName("bucketing")
    class Bucketing {

        @Test
        @DisplayName("groups endpoints by literal first segment")
        void groupsByFirstSegment() {
            var index = RouteIndex.build(
                    List.of(endpoint("/api/users", "GET"), endpoint("/api/orders", "GET"), endpoint("/health", "GET")));

            assertTrue(index.bucketKeys().containsAll(Set.of("api", "health")));
            assertEquals(0, index.wildcardCount());
        }

        @Test
        @DisplayName("routes parameterized first segments to wildcard list")
        void parameterizedFirstSegmentIsWildcard() {
            var index = RouteIndex.build(List.of(endpoint("/{tenant}/api", "GET"), endpoint("/api/users", "GET")));

            assertEquals(Set.of("api"), index.bucketKeys());
            assertEquals(1, index.wildcardCount());
        }

        @Test
        @DisplayName("routes star-prefixed paths to wildcard list")
        void starPrefixedPathIsWildcard() {
            var index = RouteIndex.build(List.of(endpoint("/**/audit", "GET")));

            assertTrue(index.bucketKeys().isEmpty());
            assertEquals(1, index.wildcardCount());
        }
    }

    @Nested
    @DisplayName("findMatch")
    class FindMatch {

        @Test
        @DisplayName("matches static path against same-bucket endpoint")
        void matchesStaticPath() {
            var endpoints = List.of(endpoint("/api/users", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/api/users", "GET");

            assertTrue(match.isPresent());
            assertEquals("/api/users", match.get().targetPath());
        }

        @Test
        @DisplayName("treats regex metacharacters as route literals")
        void treatsRegexMetacharactersAsLiterals() {
            var endpoints = List.of(endpoint("/releases/v1.0+final", "GET"));
            var index = RouteIndex.build(endpoints);

            assertTrue(index.findMatch(service(endpoints), "/releases/v1.0+final", "GET")
                    .isPresent());
            assertFalse(index.findMatch(service(endpoints), "/releases/v1x000final", "GET")
                    .isPresent());
        }

        @Test
        @DisplayName("returns empty when first segment does not match any bucket or wildcard")
        void returnsEmptyWhenNothingMatches() {
            var endpoints = List.of(endpoint("/api/users", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/admin/users", "GET");

            assertFalse(match.isPresent());
        }

        @Test
        @DisplayName("matches wildcard endpoint when bucket has no candidate")
        void matchesWildcardWhenBucketEmpty() {
            var endpoints = List.of(endpoint("/{tenant}/api", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/acme/api", "GET");

            assertTrue(match.isPresent());
            assertEquals("acme", match.get().pathVariables().get("tenant"));
        }

        @Test
        @DisplayName("preserves first-match-wins across mixed bucket and wildcard candidates")
        void preservesRegistrationOrderAcrossLists() {
            // Wildcard endpoint registered FIRST: it must win for /acme/users.
            var wildcardFirst = endpoint("/{tenant}/users", "GET");
            var staticSecond = endpoint("/acme/users", "GET");
            var endpoints = List.of(wildcardFirst, staticSecond);
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/acme/users", "GET");

            assertTrue(match.isPresent());
            assertEquals("acme", match.get().pathVariables().get("tenant"));
            assertEquals(wildcardFirst, match.get().endpointConfig());
        }

        @Test
        @DisplayName("preserves first-match-wins when bucket candidate registered first")
        void bucketFirstWinsOverWildcard() {
            var staticFirst = endpoint("/acme/users", "GET");
            var wildcardSecond = endpoint("/{tenant}/users", "GET");
            var endpoints = List.of(staticFirst, wildcardSecond);
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/acme/users", "GET");

            assertTrue(match.isPresent());
            assertEquals(staticFirst, match.get().endpointConfig());
            assertTrue(match.get().pathVariables().isEmpty());
        }

        @Test
        @DisplayName("matches root path")
        void matchesRootPath() {
            var endpoints = List.of(endpoint("/", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/", "GET");

            assertTrue(match.isPresent());
        }

        @Test
        @DisplayName("rejects requests with non-matching method when method list is explicit")
        void rejectsNonMatchingMethod() {
            var endpoints = List.of(endpoint("/api/users", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/api/users", "POST");

            assertFalse(match.isPresent());
        }

        @Test
        @DisplayName("accepts any method when endpoint is registered for *")
        void wildcardMethodMatches() {
            var endpoints = List.of(endpoint("/api/users", "*"));
            var index = RouteIndex.build(endpoints);

            assertTrue(index.findMatch(service(endpoints), "/api/users", "GET").isPresent());
            assertTrue(
                    index.findMatch(service(endpoints), "/api/users", "DELETE").isPresent());
        }

        @Test
        @DisplayName("applies path rewrite using extracted variables")
        void appliesPathRewrite() {
            var endpoints = List.of(endpointWithRewrite("/api/users/{id}", "/v2/users/{id}", "GET"));
            var index = RouteIndex.build(endpoints);

            var match = index.findMatch(service(endpoints), "/api/users/42", "GET");

            assertTrue(match.isPresent());
            assertEquals("/v2/users/42", match.get().targetPath());
        }

        @Test
        @DisplayName("matches double-star wildcard segments")
        void matchesDoubleStar() {
            var endpoints = List.of(endpoint("/api/**", "GET"));
            var index = RouteIndex.build(endpoints);

            assertTrue(index.findMatch(service(endpoints), "/api/users/42/orders", "GET")
                    .isPresent());
        }
    }
}
