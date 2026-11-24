package aussie;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.model.common.ValidationResult;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistrationValidator;
import aussie.core.service.routing.ServiceRegistry;

@QuarkusTest
@TestProfile(GatewayRequestMetricsIntegrationTest.MetricsProfile.class)
class GatewayRequestMetricsIntegrationTest {

    private static final String SERVICE_ID = "streaming-metrics-service";

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ServiceRegistry serviceRegistry;

    private WireMockServer backend;

    @BeforeAll
    static void allowLoopbackTestBackends() {
        var validator = mock(ServiceRegistrationValidator.class);
        when(validator.validate(any())).thenReturn(ValidationResult.valid());
        QuarkusMock.installMockForType(validator, ServiceRegistrationValidator.class);
    }

    @BeforeEach
    void setUp() {
        backend = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        backend.start();
    }

    @AfterEach
    void tearDown() {
        backend.stop();
        serviceRegistry
                .getAllServices()
                .await()
                .atMost(java.time.Duration.ofSeconds(5))
                .forEach(service -> serviceRegistry
                        .unregister(service.serviceId())
                        .await()
                        .atMost(java.time.Duration.ofSeconds(5)));
    }

    @Test
    void recordsTheStatusWhenAStreamingProxyResponseCompletes() {
        backend.stubFor(WireMock.get(WireMock.urlEqualTo("/api/failure"))
                .willReturn(WireMock.aResponse().withStatus(503).withBody("unavailable")));
        serviceRegistry
                .register(ServiceRegistration.builder(SERVICE_ID)
                        .baseUrl("http://localhost:" + backend.port())
                        .defaultVisibility(EndpointVisibility.PUBLIC)
                        .defaultAuthRequired(false)
                        .endpoints(List.of())
                        .build())
                .await()
                .atMost(java.time.Duration.ofSeconds(5));

        given().when().get("/" + SERVICE_ID + "/api/failure").then().statusCode(503);

        var counter = meterRegistry
                .find("aussie.requests.total")
                .tag("service_id", SERVICE_ID)
                .tag("method", "GET")
                .tag("status", "503")
                .tag("status_class", "5xx")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    public static class MetricsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var overrides = new HashMap<String, String>();
            overrides.put("aussie.telemetry.enabled", "true");
            overrides.put("aussie.telemetry.metrics.enabled", "true");
            return overrides;
        }
    }
}
