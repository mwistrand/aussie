package aussie;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
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
@DisplayName("Pass-Through Integration Tests")
class PassThroughIntegrationTest {

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
        serviceRegistry.getAllServices().forEach(s -> serviceRegistry.unregister(s.serviceId()));
    }

    @Nested
    @DisplayName("Basic Pass-Through Functionality")
    class BasicPassThroughTests {

        @Test
        @DisplayName("Should pass through GET request to registered service")
        void shouldPassThroughGetRequest() {
            backendServer.stubFor(get(urlEqualTo("/api/health"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\": \"healthy\"}")));

            registerService("my-service");

            given().when()
                    .get("/my-service/api/health")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("status", is("healthy"));

            backendServer.verify(getRequestedFor(urlEqualTo("/api/health")));
        }

        @Test
        @DisplayName("Should pass through POST request with body")
        void shouldPassThroughPostRequest() {
            backendServer.stubFor(post(urlEqualTo("/api/users"))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\": 1, \"name\": \"alice\"}")));

            registerService("user-service");

            given().contentType(ContentType.JSON)
                    .body("{\"name\": \"alice\"}")
                    .when()
                    .post("/user-service/api/users")
                    .then()
                    .statusCode(201)
                    .body("id", is(1))
                    .body("name", is("alice"));

            backendServer.verify(postRequestedFor(urlEqualTo("/api/users")).withRequestBody(containing("alice")));
        }

        @Test
        @DisplayName("Should pass through PUT request")
        void shouldPassThroughPutRequest() {
            backendServer.stubFor(put(urlEqualTo("/api/users/1"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"id\": 1, \"name\": \"bob\"}")));

            registerService("user-service");

            given().contentType(ContentType.JSON)
                    .body("{\"name\": \"bob\"}")
                    .when()
                    .put("/user-service/api/users/1")
                    .then()
                    .statusCode(200);

            backendServer.verify(putRequestedFor(urlEqualTo("/api/users/1")));
        }

        @Test
        @DisplayName("Should pass through DELETE request")
        void shouldPassThroughDeleteRequest() {
            backendServer.stubFor(
                    delete(urlEqualTo("/api/users/1")).willReturn(aResponse().withStatus(204)));

            registerService("user-service");

            given().when().delete("/user-service/api/users/1").then().statusCode(204);

            backendServer.verify(deleteRequestedFor(urlEqualTo("/api/users/1")));
        }

        @Test
        @DisplayName("Should pass through deeply nested paths")
        void shouldPassThroughDeeplyNestedPaths() {
            backendServer.stubFor(get(urlEqualTo("/api/v1/users/123/posts/456/comments"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"comments\": []}")));

            registerService("blog-service");

            given().when()
                    .get("/blog-service/api/v1/users/123/posts/456/comments")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("comments", is(java.util.Collections.emptyList()));

            backendServer.verify(getRequestedFor(urlEqualTo("/api/v1/users/123/posts/456/comments")));
        }

        @Test
        @DisplayName("Should forward custom headers to backend")
        void shouldForwardCustomHeaders() {
            backendServer.stubFor(get(urlEqualTo("/api/data"))
                    .willReturn(aResponse().withStatus(200).withBody("OK")));

            registerService("data-service");

            given().header("X-Custom-Header", "custom-value")
                    .header("Authorization", "Bearer token123")
                    .when()
                    .get("/data-service/api/data")
                    .then()
                    .statusCode(200);

            backendServer.verify(getRequestedFor(urlEqualTo("/api/data"))
                    .withHeader("X-Custom-Header", WireMock.equalTo("custom-value"))
                    .withHeader("Authorization", WireMock.equalTo("Bearer token123")));
        }

        @Test
        @DisplayName("Should include RFC 7239 Forwarded header")
        void shouldIncludeForwardedHeader() {
            backendServer.stubFor(get(urlEqualTo("/api/check"))
                    .willReturn(aResponse().withStatus(200).withBody("OK")));

            registerService("check-service");

            given().when().get("/check-service/api/check").then().statusCode(200);

            backendServer.verify(
                    getRequestedFor(urlEqualTo("/api/check")).withHeader("Forwarded", containing("proto=")));
        }

        @Test
        @DisplayName("Should return response headers from backend")
        void shouldReturnResponseHeaders() {
            backendServer.stubFor(get(urlEqualTo("/api/headers"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("X-Backend-Header", "backend-value")
                            .withHeader("X-Request-Id", "req-123")
                            .withBody("OK")));

            registerService("header-service");

            given().when()
                    .get("/header-service/api/headers")
                    .then()
                    .statusCode(200)
                    .header("X-Backend-Header", "backend-value")
                    .header("X-Request-Id", "req-123");
        }
    }

    @Nested
    @DisplayName("Service Not Found")
    class ServiceNotFoundTests {

        @Test
        @DisplayName("Should return 404 for unregistered service")
        void shouldReturn404ForUnregisteredService() {
            given().when()
                    .get("/nonexistent-service/api/health")
                    .then()
                    .statusCode(404)
                    .body(containsString("Service not found"));
        }

        @Test
        @DisplayName("Should not conflict with admin path")
        void shouldNotConflictWithAdminPath() {
            given().when().get("/admin/services").then().statusCode(200);
        }

        @Test
        @DisplayName("Should not conflict with gateway path")
        void shouldNotConflictWithGatewayPath() {
            given().when().get("/gateway/some/path").then().statusCode(404).body(containsString("Not found"));
        }
    }

    @Nested
    @DisplayName("Backend Errors")
    class BackendErrorTests {

        @Test
        @DisplayName("Should forward 500 error from backend")
        void shouldForward500Error() {
            backendServer.stubFor(get(urlEqualTo("/api/error"))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            registerService("error-service");

            given().when().get("/error-service/api/error").then().statusCode(500);
        }

        @Test
        @DisplayName("Should forward 404 from backend")
        void shouldForward404FromBackend() {
            backendServer.stubFor(get(urlEqualTo("/api/missing"))
                    .willReturn(aResponse().withStatus(404).withBody("Not Found")));

            registerService("missing-service");

            given().when().get("/missing-service/api/missing").then().statusCode(404);
        }

        @Test
        @DisplayName("Should return 502 when backend is unreachable")
        void shouldReturn502WhenBackendUnreachable() {
            var service = ServiceRegistration.builder("unreachable-service")
                    .displayName("Unreachable Service")
                    .baseUrl("http://localhost:59999")
                    .endpoints(List.of())
                    .build();
            serviceRegistry.register(service);

            given().when()
                    .get("/unreachable-service/api/health")
                    .then()
                    .statusCode(502)
                    .body(containsString("Error forwarding request"));
        }
    }

    @Nested
    @DisplayName("Access Control for Pass-Through")
    class AccessControlTests {

        @Test
        @DisplayName("Should allow pass-through for service without access config")
        void shouldAllowPassThroughWithoutAccessConfig() {
            backendServer.stubFor(get(urlEqualTo("/api/data"))
                    .willReturn(aResponse().withStatus(200).withBody("public data")));

            registerService("public-service");

            given().header("X-Forwarded-For", "203.0.113.50")
                    .when()
                    .get("/public-service/api/data")
                    .then()
                    .statusCode(200)
                    .body(containsString("public data"));
        }

        @Test
        @DisplayName("Should deny pass-through for service with access config from unauthorized IP")
        void shouldDenyPassThroughWithAccessConfigFromUnauthorizedIp() {
            backendServer.stubFor(get(urlEqualTo("/api/secret"))
                    .willReturn(aResponse().withStatus(200).withBody("secret data")));

            var accessConfig =
                    new ServiceAccessConfig(Optional.of(List.of("172.16.0.0/12")), Optional.empty(), Optional.empty());

            var service = ServiceRegistration.builder("restricted-service")
                    .displayName("Restricted Service")
                    .baseUrl("http://localhost:" + backendServer.port())
                    .endpoints(List.of())
                    .accessConfig(accessConfig)
                    .build();
            serviceRegistry.register(service);

            given().header("X-Forwarded-For", "203.0.113.50")
                    .when()
                    .get("/restricted-service/api/secret")
                    .then()
                    .statusCode(403)
                    .body(containsString("Access denied"));
        }

        @Test
        @DisplayName("Should allow pass-through for service with access config from authorized IP")
        void shouldAllowPassThroughWithAccessConfigFromAuthorizedIp() {
            backendServer.stubFor(get(urlEqualTo("/api/secret"))
                    .willReturn(aResponse().withStatus(200).withBody("secret data")));

            var accessConfig =
                    new ServiceAccessConfig(Optional.of(List.of("172.16.0.0/12")), Optional.empty(), Optional.empty());

            var service = ServiceRegistration.builder("restricted-service")
                    .displayName("Restricted Service")
                    .baseUrl("http://localhost:" + backendServer.port())
                    .endpoints(List.of())
                    .accessConfig(accessConfig)
                    .build();
            serviceRegistry.register(service);

            given().header("X-Forwarded-For", "172.16.1.1")
                    .when()
                    .get("/restricted-service/api/secret")
                    .then()
                    .statusCode(200)
                    .body(containsString("secret data"));
        }
    }

    @Nested
    @DisplayName("Pass-Through with Registered Endpoints")
    class PassThroughWithEndpointsTests {

        @Test
        @DisplayName("Should pass through paths not matching registered endpoints")
        void shouldPassThroughUnregisteredPaths() {
            backendServer.stubFor(get(urlEqualTo("/api/health"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\": \"healthy\"}")));

            backendServer.stubFor(get(urlEqualTo("/api/other"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"data\": \"other\"}")));

            var endpoint =
                    new EndpointConfig("/api/health", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("mixed-service")
                    .displayName("Mixed Service")
                    .baseUrl("http://localhost:" + backendServer.port())
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service);

            given().when()
                    .get("/mixed-service/api/health")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("status", is("healthy"));

            given().when()
                    .get("/mixed-service/api/other")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("data", is("other"));
        }
    }

    private void registerService(String serviceId) {
        var service = ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl("http://localhost:" + backendServer.port())
                .endpoints(List.of())
                .build();
        serviceRegistry.register(service);
    }
}
