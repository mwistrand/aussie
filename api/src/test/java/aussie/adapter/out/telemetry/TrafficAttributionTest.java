package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.service.ServiceRegistration;

@DisplayName("TrafficAttribution")
class TrafficAttributionTest {

    private ServiceRegistration testService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8080"))
                .build();
    }

    private TelemetryConfig.AttributionConfig attributionConfig(String tenantHeader, String clientAppHeader) {
        var config = mock(TelemetryConfig.AttributionConfig.class);
        when(config.tenantHeader()).thenReturn(tenantHeader);
        when(config.clientAppHeader()).thenReturn(clientAppHeader);
        return config;
    }

    @Nested
    @DisplayName("from factory method")
    class FromFactory {

        @Test
        @DisplayName("extracts serviceId from ServiceRegistration")
        void extractsServiceId() {
            var request = new GatewayRequest("GET", "/path", Map.of(), null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertEquals("my-svc", attribution.serviceId());
        }

        @Test
        @DisplayName("uses authenticatedTeamId for teamId")
        void usesAuthenticatedTeamId() {
            var request = new GatewayRequest("GET", "/path", Map.of(), null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-alpha", config);

            assertEquals("team-alpha", attribution.teamId());
        }

        @Test
        @DisplayName("sets teamId to null when authenticatedTeamId is null")
        void nullTeamIdWhenUnauthenticated() {
            var request = new GatewayRequest("GET", "/path", Map.of(), null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, null, config);

            assertNull(attribution.teamId());
        }

        @Test
        @DisplayName("extracts tenantId from configured header")
        void extractsTenantId() {
            var headers = Map.of("X-Tenant-ID", List.of("tenant-42"));
            var request = new GatewayRequest("GET", "/path", headers, null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertEquals("tenant-42", attribution.tenantId());
        }

        @Test
        @DisplayName("returns null tenantId when header is absent")
        void nullTenantIdWhenHeaderAbsent() {
            var request = new GatewayRequest("GET", "/path", Map.of(), null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertNull(attribution.tenantId());
        }

        @Test
        @DisplayName("extracts clientApplication from configured header")
        void extractsClientApplication() {
            var headers = Map.of("X-Client-Application", List.of("mobile-app"));
            var request = new GatewayRequest("GET", "/path", headers, null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertEquals("mobile-app", attribution.clientApplication());
        }

        @Test
        @DisplayName("returns null clientApplication when header is absent")
        void nullClientApplicationWhenHeaderAbsent() {
            var request = new GatewayRequest("GET", "/path", Map.of(), null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertNull(attribution.clientApplication());
        }

        @Test
        @DisplayName("returns null for header with empty values list")
        void nullForEmptyValuesList() {
            var headers = Map.of("X-Tenant-ID", List.<String>of());
            var request = new GatewayRequest("GET", "/path", headers, null, null, "127.0.0.1");
            var service = testService("my-svc");
            var config = attributionConfig("X-Tenant-ID", "X-Client-Application");

            var attribution = TrafficAttribution.from(request, service, "team-1", config);

            assertNull(attribution.tenantId());
        }
    }

    @Nested
    @DisplayName("orUnknown methods")
    class OrUnknownMethods {

        @Test
        @DisplayName("serviceIdOrUnknown returns serviceId when present")
        void serviceIdOrUnknownPresent() {
            var attribution = new TrafficAttribution("my-svc", null, null, null, null);
            assertEquals("my-svc", attribution.serviceIdOrUnknown());
        }

        @Test
        @DisplayName("serviceIdOrUnknown returns 'unknown' when null")
        void serviceIdOrUnknownNull() {
            var attribution = new TrafficAttribution(null, null, null, null, null);
            assertEquals("unknown", attribution.serviceIdOrUnknown());
        }

        @Test
        @DisplayName("teamIdOrUnknown returns teamId when present")
        void teamIdOrUnknownPresent() {
            var attribution = new TrafficAttribution(null, "team-1", null, null, null);
            assertEquals("team-1", attribution.teamIdOrUnknown());
        }

        @Test
        @DisplayName("teamIdOrUnknown returns 'unknown' when null")
        void teamIdOrUnknownNull() {
            var attribution = new TrafficAttribution(null, null, null, null, null);
            assertEquals("unknown", attribution.teamIdOrUnknown());
        }

        @Test
        @DisplayName("tenantIdOrUnknown returns tenantId when present")
        void tenantIdOrUnknownPresent() {
            var attribution = new TrafficAttribution(null, null, "tenant-42", null, null);
            assertEquals("tenant-42", attribution.tenantIdOrUnknown());
        }

        @Test
        @DisplayName("tenantIdOrUnknown returns 'unknown' when null")
        void tenantIdOrUnknownNull() {
            var attribution = new TrafficAttribution(null, null, null, null, null);
            assertEquals("unknown", attribution.tenantIdOrUnknown());
        }

        @Test
        @DisplayName("environmentOrUnknown returns environment when present")
        void environmentOrUnknownPresent() {
            var attribution = new TrafficAttribution(null, null, null, null, "prod");
            assertEquals("prod", attribution.environmentOrUnknown());
        }

        @Test
        @DisplayName("environmentOrUnknown returns 'unknown' when null")
        void environmentOrUnknownNull() {
            var attribution = new TrafficAttribution(null, null, null, null, null);
            assertEquals("unknown", attribution.environmentOrUnknown());
        }
    }
}
