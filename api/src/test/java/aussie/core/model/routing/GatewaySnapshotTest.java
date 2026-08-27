package aussie.core.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import aussie.core.model.common.CorsConfig;
import aussie.core.model.service.ServiceRegistration;

class GatewaySnapshotTest {

    @Test
    void routingIsIndependentOfRegistrationOrder() {
        final var expected = List.of(
                service("alpha", endpoint("/alpha/{id}", "GET")),
                service("beta", endpoint("/beta/*", "GET")),
                service("gamma", endpoint("/gamma/**", "POST")));

        for (var seed = 0; seed < 100; seed++) {
            final var shuffled = new ArrayList<>(expected);
            Collections.shuffle(shuffled, new Random(seed));
            final var snapshot = GatewaySnapshot.build(shuffled);

            assertEquals("alpha", snapshot.match("/alpha/42", "GET").service().serviceId());
            assertEquals("beta", snapshot.match("/beta/item", "GET").service().serviceId());
            assertEquals("gamma", snapshot.match("/gamma/a/b", "POST").service().serviceId());
            assertNull(snapshot.match("/missing", "GET"));
        }
    }

    @Test
    void rejectsCrossServiceRouteConflicts() {
        final var exact = service("exact", endpoint("/users/me", "GET"));
        final var parameter = service("parameter", endpoint("/users/{id}", "GET"));

        assertThrows(IllegalArgumentException.class, () -> GatewaySnapshot.build(List.of(exact, parameter)));
    }

    @Test
    void allowsSamePathForDisjointMethods() {
        final var get = service("reader", endpoint("/users/{id}", "GET"));
        final var post = service("writer", endpoint("/users/{userId}", "POST"));

        final var snapshot = GatewaySnapshot.build(List.of(get, post));

        assertEquals("reader", snapshot.match("/users/42", "GET").service().serviceId());
        assertEquals("writer", snapshot.match("/users/42", "POST").service().serviceId());
    }

    @Test
    void allowsDisjointWildcardRoutes() {
        final var alpha = service("alpha", endpoint("/alpha/**", "GET"));
        final var beta = service("beta", endpoint("/beta/items", "GET"));
        final var image = service("image", endpoint("/files/image-*", "GET"));
        final var video = service("video", endpoint("/files/video-*", "GET"));

        final var snapshot = GatewaySnapshot.build(List.of(alpha, beta, image, video));

        assertEquals("alpha", snapshot.match("/alpha/items", "GET").service().serviceId());
        assertEquals("video", snapshot.match("/files/video-1", "GET").service().serviceId());
    }

    @Test
    void rejectsOverlappingRoutesWithMultipleDoubleStars() {
        final var alphaFirst = service("alpha-first", endpoint("/root/**/alpha/**/omega/**", "GET"));
        final var omegaFirst = service("omega-first", endpoint("/root/**/omega/**/alpha/**", "GET"));

        assertThrows(IllegalArgumentException.class, () -> GatewaySnapshot.build(List.of(alphaFirst, omegaFirst)));
    }

    @Test
    void indexesServiceCorsPoliciesByOrigin() {
        final var service = ServiceRegistration.builder("demo")
                .baseUrl(URI.create("http://192.0.2.10"))
                .corsConfig(CorsConfig.builder()
                        .allowedOrigins("http://localhost:3000")
                        .allowCredentials(true)
                        .build())
                .build();

        final var config = GatewaySnapshot.build(List.of(service)).corsConfigForOrigin("http://localhost:3000");

        assertTrue(config.isPresent());
        assertTrue(config.get().allowCredentials());
    }

    private static EndpointConfig endpoint(String path, String method) {
        return new EndpointConfig(path, Set.of(method), EndpointVisibility.PUBLIC, Optional.empty());
    }

    private static ServiceRegistration service(String id, EndpointConfig endpoint) {
        return ServiceRegistration.builder(id)
                .baseUrl(URI.create("http://192.0.2.10"))
                .endpoints(List.of(endpoint))
                .build();
    }
}
