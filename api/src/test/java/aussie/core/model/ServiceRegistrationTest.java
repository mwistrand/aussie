package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceRegistration")
class ServiceRegistrationTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build with minimal required fields")
        void shouldBuildWithMinimalFields() {
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            assertEquals("my-service", service.serviceId());
            assertEquals("my-service", service.displayName()); // defaults to serviceId
            assertEquals(URI.create("http://localhost:8080"), service.baseUrl());
            assertTrue(service.endpoints().isEmpty());
            assertFalse(service.accessConfig().isPresent());
        }

        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var accessConfig =
                    new ServiceAccessConfig(Optional.of(List.of("10.0.0.0/8")), Optional.empty(), Optional.empty());

            var service = ServiceRegistration.builder("my-service")
                    .displayName("My Service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of(endpoint))
                    .accessConfig(accessConfig)
                    .build();

            assertEquals("my-service", service.serviceId());
            assertEquals("My Service", service.displayName());
            assertEquals(1, service.endpoints().size());
            assertTrue(service.accessConfig().isPresent());
        }

        @Test
        @DisplayName("Should throw when serviceId is null")
        void shouldThrowWhenServiceIdNull() {
            assertThrows(IllegalArgumentException.class, () -> ServiceRegistration.builder(null)
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build());
        }

        @Test
        @DisplayName("Should throw when baseUrl is null")
        void shouldThrowWhenBaseUrlNull() {
            // URI.create(null) throws NullPointerException
            assertThrows(NullPointerException.class, () -> ServiceRegistration.builder("my-service")
                    .baseUrl((String) null)
                    .endpoints(List.of())
                    .build());
        }

        @Test
        @DisplayName("Should accept URI for baseUrl")
        void shouldAcceptUriForBaseUrl() {
            var uri = URI.create("http://localhost:8080");
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl(uri)
                    .endpoints(List.of())
                    .build();

            assertEquals(uri, service.baseUrl());
        }
    }

    @Nested
    @DisplayName("Record")
    class RecordTests {

        @Test
        @DisplayName("Should create record directly")
        void shouldCreateRecordDirectly() {
            var service = new ServiceRegistration(
                    "my-service",
                    "My Service",
                    URI.create("http://localhost:8080"),
                    null, // routePrefix - defaults to /my-service
                    null, // defaultVisibility - defaults to PRIVATE
                    false, // defaultAuthRequired
                    null, // visibilityRules - defaults to empty list
                    List.of(),
                    Optional.empty(),
                    Optional.empty(), // corsConfig
                    Optional.empty(), // permissionPolicy
                    1L); // version

            assertNotNull(service);
            assertEquals("my-service", service.serviceId());
            assertEquals("/my-service", service.routePrefix());
            assertEquals(EndpointVisibility.PRIVATE, service.defaultVisibility());
        }

        @Test
        @DisplayName("Should have value-based equality")
        void shouldHaveValueBasedEquality() {
            var service1 = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            var service2 = ServiceRegistration.builder("my-service")
                    .baseUrl("http://localhost:8080")
                    .endpoints(List.of())
                    .build();

            assertEquals(service1, service2);
            assertEquals(service1.hashCode(), service2.hashCode());
        }
    }
}
