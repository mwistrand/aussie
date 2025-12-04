package aussie;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceAccessConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.service.ServiceRegistry;

@QuarkusTest
@DisplayName("Access Control Integration Tests")
class AccessControlIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    private WireMockServer backendServer;

    @BeforeEach
    void setUp() {
        backendServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backendServer.start();

        backendServer.stubFor(get(urlEqualTo("/api/public"))
                .willReturn(aResponse().withStatus(200).withBody("public data")));

        backendServer.stubFor(get(urlEqualTo("/api/private"))
                .willReturn(aResponse().withStatus(200).withBody("private data")));
    }

    @AfterEach
    void tearDown() {
        if (backendServer != null) {
            backendServer.stop();
        }
        serviceRegistry.getAllServices().forEach(s -> serviceRegistry.unregister(s.serviceId()));
    }

    @Nested
    @DisplayName("Public Endpoints")
    class PublicEndpointTests {

        @Test
        @DisplayName("Should allow access to public endpoints from any source")
        void shouldAllowPublicEndpointAccess() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/public", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty()));

            given().when().get("/gateway/api/public").then().statusCode(200).body(containsString("public data"));
        }
    }

    @Nested
    @DisplayName("Private Endpoints - Global Access Control")
    class PrivateEndpointGlobalAccessTests {

        @Test
        @DisplayName("Should allow private endpoint access from allowed IP (127.0.0.1)")
        void shouldAllowPrivateEndpointFromAllowedIp() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty()));

            // Test config allows 127.0.0.1
            given().header("X-Forwarded-For", "127.0.0.1")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(200)
                    .body(containsString("private data"));
        }

        @Test
        @DisplayName("Should allow private endpoint access from allowed CIDR range")
        void shouldAllowPrivateEndpointFromAllowedCidr() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty()));

            // Test config allows 10.0.0.0/8
            given().header("X-Forwarded-For", "10.1.2.3")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(200)
                    .body(containsString("private data"));
        }

        @Test
        @DisplayName("Should deny private endpoint access from unauthorized IP")
        void shouldDenyPrivateEndpointFromUnauthorizedIp() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty()));

            // 203.0.113.0/24 (TEST-NET-3) is not in our allowed list
            given().header("X-Forwarded-For", "203.0.113.50")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(403)
                    .body(containsString("Access denied"));
        }

        @Test
        @DisplayName("Should allow private endpoint access from allowed domain")
        void shouldAllowPrivateEndpointFromAllowedDomain() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty()));

            // Test config allows internal.test.com
            given().header("X-Forwarded-Host", "internal.test.com")
                    .header("X-Forwarded-For", "127.0.0.1")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(200);
        }

        @Test
        @DisplayName("Should allow private endpoint access from allowed subdomain")
        void shouldAllowPrivateEndpointFromAllowedSubdomain() {
            registerServiceWithEndpoints(
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty()));

            // Test config allows *.internal.test.com
            given().header("X-Forwarded-Host", "api.internal.test.com")
                    .header("X-Forwarded-For", "127.0.0.1")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(200);
        }
    }

    @Nested
    @DisplayName("Private Endpoints - Service-Specific Access Control")
    class PrivateEndpointServiceAccessTests {

        @Test
        @DisplayName("Should use service-specific access config when provided")
        void shouldUseServiceSpecificAccessConfig() {
            // Register service with specific access config that allows only 172.16.0.0/12
            var accessConfig =
                    new ServiceAccessConfig(Optional.of(List.of("172.16.0.0/12")), Optional.empty(), Optional.empty());

            var endpoint =
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty());
            var service = ServiceRegistration.builder("restricted-service")
                    .displayName("Restricted Service")
                    .baseUrl("http://localhost:" + backendServer.port())
                    .endpoints(List.of(endpoint))
                    .accessConfig(accessConfig)
                    .build();
            serviceRegistry.register(service);

            // Should allow 172.16.x.x (in the service's allowed range)
            given().header("X-Forwarded-For", "172.16.1.1")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(200);

            // Should deny 10.0.0.1 (in global allowed range but not in service's range)
            given().header("X-Forwarded-For", "10.0.0.1")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(403);
        }
    }

    @Nested
    @DisplayName("Mixed Visibility Endpoints")
    class MixedVisibilityTests {

        @Test
        @DisplayName("Should handle service with both public and private endpoints")
        void shouldHandleMixedVisibilityEndpoints() {
            var publicEndpoint =
                    new EndpointConfig("/api/public", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var privateEndpoint =
                    new EndpointConfig("/api/private", Set.of("GET"), EndpointVisibility.PRIVATE, Optional.empty());

            var service = ServiceRegistration.builder("mixed-service")
                    .displayName("Mixed Service")
                    .baseUrl("http://localhost:" + backendServer.port())
                    .endpoints(List.of(publicEndpoint, privateEndpoint))
                    .build();
            serviceRegistry.register(service);

            // Public endpoint should be accessible
            given().header("X-Forwarded-For", "203.0.113.50")
                    .when()
                    .get("/gateway/api/public")
                    .then()
                    .statusCode(200);

            // Private endpoint should be denied for unauthorized IP
            given().header("X-Forwarded-For", "203.0.113.50")
                    .when()
                    .get("/gateway/api/private")
                    .then()
                    .statusCode(403);
        }
    }

    private void registerServiceWithEndpoints(EndpointConfig... endpoints) {
        var service = ServiceRegistration.builder("test-service")
                .displayName("Test Service")
                .baseUrl("http://localhost:" + backendServer.port())
                .endpoints(List.of(endpoints))
                .build();
        serviceRegistry.register(service);
    }
}
