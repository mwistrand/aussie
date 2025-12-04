package aussie;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import com.github.tomakehurst.wiremock.client.WireMock;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceRegistration;
import aussie.core.service.ServiceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

@QuarkusTest
@DisplayName("Gateway Integration Tests")
class GatewayIntegrationTest {

    @Inject
    ServiceRegistry serviceRegistry;

    private WireMockServer backendServer;

    @BeforeEach
    void setUp() {
        backendServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backendServer.start();
    }

    @AfterEach
    void tearDown() {
        if (backendServer != null) {
            backendServer.stop();
        }
        // Clear all registered services
        serviceRegistry.getAllServices().forEach(s -> serviceRegistry.unregister(s.serviceId()));
    }

    @Nested
    @DisplayName("Happy Path - Request Proxying")
    class HappyPathTests {

        @Test
        @DisplayName("Should proxy GET request to registered service")
        void shouldProxyGetRequest() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/users"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"users\": [\"alice\", \"bob\"]}")));

            registerService("user-service", "/api/users", Set.of("GET"));

            // Act & Assert
            given()
                .when()
                .get("/gateway/api/users")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("users[0]", is("alice"))
                .body("users[1]", is("bob"));

            backendServer.verify(getRequestedFor(urlEqualTo("/api/users")));
        }

        @Test
        @DisplayName("Should proxy POST request with body")
        void shouldProxyPostRequestWithBody() {
            // Arrange
            backendServer.stubFor(post(urlEqualTo("/api/users"))
                .willReturn(aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\": 123, \"name\": \"charlie\"}")));

            registerService("user-service", "/api/users", Set.of("POST"));

            // Act & Assert
            given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"charlie\"}")
                .when()
                .post("/gateway/api/users")
                .then()
                .statusCode(201)
                .body("id", is(123))
                .body("name", is("charlie"));

            backendServer.verify(postRequestedFor(urlEqualTo("/api/users"))
                .withRequestBody(containing("charlie")));
        }

        @Test
        @DisplayName("Should forward custom headers to backend")
        void shouldForwardCustomHeaders() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("OK")));

            registerService("data-service", "/api/data", Set.of("GET"));

            // Act
            given()
                .header("X-Custom-Header", "custom-value")
                .header("Authorization", "Bearer token123")
                .when()
                .get("/gateway/api/data")
                .then()
                .statusCode(200);

            // Assert headers were forwarded
            backendServer.verify(getRequestedFor(urlEqualTo("/api/data"))
                .withHeader("X-Custom-Header", WireMock.equalTo("custom-value"))
                .withHeader("Authorization", WireMock.equalTo("Bearer token123")));
        }

        @Test
        @DisplayName("Should include RFC 7239 Forwarded header")
        void shouldIncludeRfc7239ForwardedHeader() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/check"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("OK")));

            registerService("check-service", "/api/check", Set.of("GET"));

            // Act
            given()
                .when()
                .get("/gateway/api/check")
                .then()
                .statusCode(200);

            // Assert Forwarded header was added
            backendServer.verify(getRequestedFor(urlEqualTo("/api/check"))
                .withHeader("Forwarded", containing("proto=")));
        }

        @Test
        @DisplayName("Should return response headers from backend")
        void shouldReturnResponseHeaders() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/headers"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("X-Backend-Header", "backend-value")
                    .withHeader("X-Request-Id", "req-123")
                    .withBody("OK")));

            registerService("header-service", "/api/headers", Set.of("GET"));

            // Act & Assert
            given()
                .when()
                .get("/gateway/api/headers")
                .then()
                .statusCode(200)
                .header("X-Backend-Header", "backend-value")
                .header("X-Request-Id", "req-123");
        }
    }

    @Nested
    @DisplayName("Route Not Found")
    class RouteNotFoundTests {

        @Test
        @DisplayName("Should return 404 for unregistered routes")
        void shouldReturn404ForUnregisteredRoute() {
            given()
                .when()
                .get("/gateway/nonexistent/path")
                .then()
                .statusCode(404)
                .body(org.hamcrest.Matchers.containsString("No route found"));
        }

        @Test
        @DisplayName("Should return 404 for wrong HTTP method")
        void shouldReturn404ForWrongMethod() {
            // Register only GET
            registerService("get-only-service", "/api/get-only", Set.of("GET"));

            given()
                .when()
                .post("/gateway/api/get-only")
                .then()
                .statusCode(404);
        }
    }

    @Nested
    @DisplayName("Backend Errors")
    class BackendErrorTests {

        @Test
        @DisplayName("Should forward 500 error from backend")
        void shouldForward500Error() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/error"))
                .willReturn(aResponse()
                    .withStatus(500)
                    .withBody("Internal Server Error")));

            registerService("error-service", "/api/error", Set.of("GET"));

            // Act & Assert
            given()
                .when()
                .get("/gateway/api/error")
                .then()
                .statusCode(500);
        }

        @Test
        @DisplayName("Should forward 404 from backend")
        void shouldForward404FromBackend() {
            // Arrange
            backendServer.stubFor(get(urlEqualTo("/api/missing"))
                .willReturn(aResponse()
                    .withStatus(404)
                    .withBody("Not Found")));

            registerService("missing-service", "/api/missing", Set.of("GET"));

            // Act & Assert
            given()
                .when()
                .get("/gateway/api/missing")
                .then()
                .statusCode(404);
        }
    }

    private void registerService(String serviceId, String path, Set<String> methods) {
        var endpoint = new EndpointConfig(path, methods, EndpointVisibility.PUBLIC, java.util.Optional.empty());
        var service = ServiceRegistration.builder(serviceId)
            .displayName(serviceId)
            .baseUrl("http://localhost:" + backendServer.port())
            .endpoints(List.of(endpoint))
            .build();
        serviceRegistry.register(service);
    }
}
