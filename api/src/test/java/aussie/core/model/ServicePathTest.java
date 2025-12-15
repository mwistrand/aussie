package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.*;

@DisplayName("ServicePath")
class ServicePathTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            var servicePath = new ServicePath("demo-service", "/api/users");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals("/api/users", servicePath.path());
        }

        @Test
        @DisplayName("should default serviceId to 'unknown' when null")
        void shouldDefaultServiceIdWhenNull() {
            var servicePath = new ServicePath(null, "/api/users");

            assertEquals(ServicePath.UNKNOWN_SERVICE, servicePath.serviceId());
        }

        @Test
        @DisplayName("should default serviceId to 'unknown' when empty")
        void shouldDefaultServiceIdWhenEmpty() {
            var servicePath = new ServicePath("", "/api/users");

            assertEquals(ServicePath.UNKNOWN_SERVICE, servicePath.serviceId());
        }

        @Test
        @DisplayName("should default path to '/' when null")
        void shouldDefaultPathWhenNull() {
            var servicePath = new ServicePath("demo-service", null);

            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should default path to '/' when empty")
        void shouldDefaultPathWhenEmpty() {
            var servicePath = new ServicePath("demo-service", "");

            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }
    }

    @Nested
    @DisplayName("Parse Method")
    class ParseTests {

        @Test
        @DisplayName("should parse path with leading slash")
        void shouldParsePathWithLeadingSlash() {
            var servicePath = ServicePath.parse("/demo-service/api/users");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals("/api/users", servicePath.path());
        }

        @Test
        @DisplayName("should parse path without leading slash")
        void shouldParsePathWithoutLeadingSlash() {
            var servicePath = ServicePath.parse("demo-service/api/users");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals("/api/users", servicePath.path());
        }

        @Test
        @DisplayName("should parse path with only service ID and leading slash")
        void shouldParsePathWithOnlyServiceIdAndLeadingSlash() {
            var servicePath = ServicePath.parse("/demo-service");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should parse path with only service ID")
        void shouldParsePathWithOnlyServiceId() {
            var servicePath = ServicePath.parse("demo-service");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should parse path with service ID and trailing slash")
        void shouldParsePathWithServiceIdAndTrailingSlash() {
            var servicePath = ServicePath.parse("/demo-service/");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should parse nested endpoint paths")
        void shouldParseNestedEndpointPaths() {
            var servicePath = ServicePath.parse("/demo-service/api/users/123/profile");

            assertEquals("demo-service", servicePath.serviceId());
            assertEquals("/api/users/123/profile", servicePath.path());
        }

        @Test
        @DisplayName("should return unknown for null path")
        void shouldReturnUnknownForNullPath() {
            var servicePath = ServicePath.parse(null);

            assertEquals(ServicePath.UNKNOWN_SERVICE, servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should return unknown for empty path")
        void shouldReturnUnknownForEmptyPath() {
            var servicePath = ServicePath.parse("");

            assertEquals(ServicePath.UNKNOWN_SERVICE, servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should return unknown for root path")
        void shouldReturnUnknownForRootPath() {
            var servicePath = ServicePath.parse("/");

            assertEquals(ServicePath.UNKNOWN_SERVICE, servicePath.serviceId());
            assertEquals(ServicePath.ROOT_PATH, servicePath.path());
        }

        @Test
        @DisplayName("should preserve original case")
        void shouldPreserveOriginalCase() {
            var servicePath = ServicePath.parse("/Demo-Service/Api/Users");

            assertEquals("Demo-Service", servicePath.serviceId());
            assertEquals("/Api/Users", servicePath.path());
        }

        @Test
        @DisplayName("should handle special characters in service ID")
        void shouldHandleSpecialCharactersInServiceId() {
            var servicePath = ServicePath.parse("/my-service-v2/api/test");

            assertEquals("my-service-v2", servicePath.serviceId());
            assertEquals("/api/test", servicePath.path());
        }

        @Test
        @DisplayName("should handle admin paths")
        void shouldHandleAdminPaths() {
            var servicePath = ServicePath.parse("/admin/services");

            assertEquals("admin", servicePath.serviceId());
            assertEquals("/services", servicePath.path());
        }

        @Test
        @DisplayName("should handle gateway paths")
        void shouldHandleGatewayPaths() {
            var servicePath = ServicePath.parse("/gateway/route/test");

            assertEquals("gateway", servicePath.serviceId());
            assertEquals("/route/test", servicePath.path());
        }

        @Test
        @DisplayName("should handle health check paths")
        void shouldHandleHealthCheckPaths() {
            var servicePath = ServicePath.parse("/q/health");

            assertEquals("q", servicePath.serviceId());
            assertEquals("/health", servicePath.path());
        }
    }
}
