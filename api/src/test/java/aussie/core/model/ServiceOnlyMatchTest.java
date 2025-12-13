package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceOnlyMatch")
class ServiceOnlyMatchTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create ServiceOnlyMatch with valid service")
        void shouldCreateWithValidService() {
            var service = createTestService();

            var match = new ServiceOnlyMatch(service);

            assertEquals(service, match.service());
        }

        @Test
        @DisplayName("Should throw when service is null")
        void shouldThrowWhenServiceNull() {
            assertThrows(IllegalArgumentException.class, () -> new ServiceOnlyMatch(null));
        }
    }

    @Nested
    @DisplayName("RouteLookupResult Implementation")
    class RouteLookupResultTests {

        @Test
        @DisplayName("Should return empty endpoint")
        void shouldReturnEmptyEndpoint() {
            var service = createTestService();

            var match = new ServiceOnlyMatch(service);

            assertTrue(match.endpoint().isEmpty());
        }

        @Test
        @DisplayName("Should return service default visibility")
        void shouldReturnServiceDefaultVisibility() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .endpoints(List.of())
                    .build();

            var match = new ServiceOnlyMatch(service);

            assertEquals(EndpointVisibility.PUBLIC, match.visibility());
        }

        @Test
        @DisplayName("Should return PRIVATE visibility when service has PRIVATE default")
        void shouldReturnPrivateVisibility() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .endpoints(List.of())
                    .build();

            var match = new ServiceOnlyMatch(service);

            assertEquals(EndpointVisibility.PRIVATE, match.visibility());
        }

        @Test
        @DisplayName("Should return service default authRequired true")
        void shouldReturnServiceAuthRequiredTrue() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultAuthRequired(true)
                    .endpoints(List.of())
                    .build();

            var match = new ServiceOnlyMatch(service);

            assertTrue(match.authRequired());
        }

        @Test
        @DisplayName("Should return service default authRequired false")
        void shouldReturnServiceAuthRequiredFalse() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultAuthRequired(false)
                    .endpoints(List.of())
                    .build();

            var match = new ServiceOnlyMatch(service);

            assertFalse(match.authRequired());
        }

        @Test
        @DisplayName("Should return service rate limit config when set")
        void shouldReturnServiceRateLimitConfig() {
            var serviceRateLimit = ServiceRateLimitConfig.of(100, 60, 200);
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .rateLimitConfig(serviceRateLimit)
                    .endpoints(List.of())
                    .build();

            var match = new ServiceOnlyMatch(service);

            assertTrue(match.rateLimitConfig().isPresent());
            assertEquals(Optional.of(100L), match.rateLimitConfig().get().requestsPerWindow());
            assertEquals(Optional.of(60L), match.rateLimitConfig().get().windowSeconds());
            assertEquals(Optional.of(200L), match.rateLimitConfig().get().burstCapacity());
        }

        @Test
        @DisplayName("Should return empty when service has no rate limit config")
        void shouldReturnEmptyWhenNoRateLimitConfig() {
            var service = createTestService();

            var match = new ServiceOnlyMatch(service);

            assertFalse(match.rateLimitConfig().isPresent());
        }
    }

    @Nested
    @DisplayName("Polymorphism with RouteLookupResult")
    class PolymorphismTests {

        @Test
        @DisplayName("Should be assignable to RouteLookupResult")
        void shouldBeAssignableToRouteLookupResult() {
            var service = createTestService();

            RouteLookupResult result = new ServiceOnlyMatch(service);

            assertEquals(service, result.service());
            assertTrue(result.endpoint().isEmpty());
        }

        @Test
        @DisplayName("Should work in pattern matching with RouteMatch")
        void shouldWorkInPatternMatching() {
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://localhost:8080")
                    .defaultVisibility(EndpointVisibility.PUBLIC)
                    .defaultAuthRequired(true)
                    .endpoints(List.of())
                    .build();

            RouteLookupResult serviceOnlyResult = new ServiceOnlyMatch(service);

            // Using pattern matching
            var visibility =
                    switch (serviceOnlyResult) {
                        case RouteMatch rm -> rm.endpointConfig().visibility();
                        case ServiceOnlyMatch sm -> sm.service().defaultVisibility();
                    };

            assertEquals(EndpointVisibility.PUBLIC, visibility);

            // Using interface method directly
            assertEquals(visibility, serviceOnlyResult.visibility());
        }
    }

    private ServiceRegistration createTestService() {
        return ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .endpoints(List.of())
                .build();
    }
}
