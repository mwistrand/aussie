package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.OperationPermission;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.*;

@DisplayName("ServiceAuthorizationService")
class ServiceAuthorizationServiceTest {

    private ServiceAuthorizationService authService;
    private DefaultPermissionPolicy defaultPolicy;

    @BeforeEach
    void setUp() {
        defaultPolicy = new DefaultPermissionPolicy();
        authService = new ServiceAuthorizationService(defaultPolicy);
    }

    @Nested
    @DisplayName("isAuthorizedForService")
    class IsAuthorizedForServiceTests {

        @Test
        @DisplayName("Should allow wildcard permission for any operation")
        void shouldAllowWildcardPermission() {
            var service = createServiceWithPolicy(null);

            assertTrue(authService.isAuthorizedForService(service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("*")));
            assertTrue(
                    authService.isAuthorizedForService(service, Permission.SERVICE_CONFIG_UPDATE_VALUE, Set.of("*")));
            assertTrue(authService.isAuthorizedForService(
                    service, Permission.SERVICE_PERMISSIONS_WRITE_VALUE, Set.of("*")));
        }

        @Test
        @DisplayName("Should deny when permissions are null")
        void shouldDenyWhenPermissionsNull() {
            var service = createServiceWithPolicy(new ServicePermissionPolicy(
                    Map.of(Permission.SERVICE_CONFIG_READ_VALUE, new OperationPermission(Set.of("admin")))));

            assertFalse(authService.isAuthorizedForService(service, Permission.SERVICE_CONFIG_READ_VALUE, null));
        }

        @Test
        @DisplayName("Should deny when permissions are empty")
        void shouldDenyWhenPermissionsEmpty() {
            var service = createServiceWithPolicy(new ServicePermissionPolicy(
                    Map.of(Permission.SERVICE_CONFIG_READ_VALUE, new OperationPermission(Set.of("admin")))));

            assertFalse(authService.isAuthorizedForService(service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of()));
        }

        @Test
        @DisplayName("Should use service policy when present")
        void shouldUseServicePolicyWhenPresent() {
            var policy = new ServicePermissionPolicy(
                    Map.of(Permission.SERVICE_CONFIG_READ_VALUE, new OperationPermission(Set.of("service-reader"))));
            var service = createServiceWithPolicy(policy);

            assertTrue(authService.isAuthorizedForService(
                    service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("service-reader")));
            // aussie:admin should NOT work because service has its own policy
            assertFalse(authService.isAuthorizedForService(
                    service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("aussie:admin")));
        }

        @Test
        @DisplayName("Should fall back to default policy when service has no policy")
        void shouldFallBackToDefaultPolicy() {
            var service = createServiceWithPolicy(null);

            // Default policy requires aussie:admin
            assertTrue(authService.isAuthorizedForService(
                    service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("aussie:admin")));
            assertFalse(authService.isAuthorizedForService(
                    service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("other-claim")));
        }

        @Test
        @DisplayName("Should fall back to default policy when service policy is empty")
        void shouldFallBackWhenPolicyEmpty() {
            var service = createServiceWithPolicy(ServicePermissionPolicy.empty());

            // Default policy requires aussie:admin
            assertTrue(authService.isAuthorizedForService(
                    service, Permission.SERVICE_CONFIG_READ_VALUE, Set.of("aussie:admin")));
        }
    }

    @Nested
    @DisplayName("canCreateService")
    class CanCreateServiceTests {

        @Test
        @DisplayName("Should allow wildcard permission")
        void shouldAllowWildcardPermission() {
            assertTrue(authService.canCreateService(Set.of("*")));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission")
        void shouldAllowAdminPermission() {
            assertTrue(authService.canCreateService(Set.of("aussie:admin")));
        }

        @Test
        @DisplayName("Should deny other permissions")
        void shouldDenyOtherPermissions() {
            assertFalse(authService.canCreateService(Set.of("other-permission")));
        }

        @Test
        @DisplayName("Should deny null permissions")
        void shouldDenyNullPermissions() {
            assertFalse(authService.canCreateService(null));
        }
    }

    private ServiceRegistration createServiceWithPolicy(ServicePermissionPolicy policy) {
        return ServiceRegistration.builder("test-service")
                .baseUrl("http://localhost:8080")
                .permissionPolicy(policy)
                .build();
    }
}
