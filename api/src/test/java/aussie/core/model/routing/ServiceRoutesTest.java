package aussie.core.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;

@DisplayName("ServiceRoutes")
class ServiceRoutesTest {

    private static EndpointConfig endpoint(String path, String... methods) {
        return new EndpointConfig(path, Set.of(methods), EndpointVisibility.PUBLIC, Optional.empty());
    }

    private static ServiceRegistration registration(List<EndpointConfig> endpoints) {
        return ServiceRegistration.builder("svc")
                .baseUrl(URI.create("http://localhost:8080"))
                .endpoints(endpoints)
                .build();
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("rejects null service")
        void rejectsNullService() {
            assertThrows(IllegalArgumentException.class, () -> new ServiceRoutes(null, RouteIndex.build(List.of())));
        }

        @Test
        @DisplayName("rejects null index")
        void rejectsNullIndex() {
            assertThrows(IllegalArgumentException.class, () -> new ServiceRoutes(registration(List.of()), null));
        }
    }

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        @DisplayName("builds a working index from the registration's endpoints")
        void buildsWorkingIndex() {
            var service = registration(List.of(endpoint("/api/users", "GET")));

            var routes = ServiceRoutes.of(service);

            assertSame(service, routes.service());
            assertNotNull(routes.matchEndpoint("/api/users", "GET"));
        }
    }

    @Nested
    @DisplayName("matchEndpoint")
    class MatchEndpoint {

        @Test
        @DisplayName("returns RouteMatch on hit")
        void returnsMatchOnHit() {
            var routes = ServiceRoutes.of(registration(List.of(endpoint("/api/users/{id}", "GET"))));

            var match = routes.matchEndpoint("/api/users/42", "GET");

            assertNotNull(match);
            assertEquals("42", match.pathVariables().get("id"));
        }

        @Test
        @DisplayName("returns null when no endpoint matches")
        void returnsNullOnMiss() {
            var routes = ServiceRoutes.of(registration(List.of(endpoint("/api/users", "GET"))));

            assertNull(routes.matchEndpoint("/admin/users", "GET"));
        }

        @Test
        @DisplayName("returns null when method does not match")
        void returnsNullOnMethodMismatch() {
            var routes = ServiceRoutes.of(registration(List.of(endpoint("/api/users", "GET"))));

            assertNull(routes.matchEndpoint("/api/users", "POST"));
        }
    }
}
