package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.OperationPermission;
import aussie.core.model.Permission;
import aussie.core.model.ServicePermissionPolicy;
import aussie.core.model.ServiceRegistration;

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

            assertTrue(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("*")));
            assertTrue(authService.isAuthorizedForService(service, Permission.CONFIG_UPDATE, Set.of("*")));
            assertTrue(authService.isAuthorizedForService(service, Permission.PERMISSIONS_WRITE, Set.of("*")));
        }

        @Test
        @DisplayName("Should deny when permissions are null")
        void shouldDenyWhenPermissionsNull() {
            var service = createServiceWithPolicy(new ServicePermissionPolicy(
                    Map.of(Permission.CONFIG_READ, new OperationPermission(Set.of("admin")))));

            assertFalse(authService.isAuthorizedForService(service, Permission.CONFIG_READ, null));
        }

        @Test
        @DisplayName("Should deny when permissions are empty")
        void shouldDenyWhenPermissionsEmpty() {
            var service = createServiceWithPolicy(new ServicePermissionPolicy(
                    Map.of(Permission.CONFIG_READ, new OperationPermission(Set.of("admin")))));

            assertFalse(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of()));
        }

        @Test
        @DisplayName("Should use service policy when present")
        void shouldUseServicePolicyWhenPresent() {
            var policy = new ServicePermissionPolicy(
                    Map.of(Permission.CONFIG_READ, new OperationPermission(Set.of("service-reader"))));
            var service = createServiceWithPolicy(policy);

            assertTrue(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("service-reader")));
            // aussie:admin should NOT work because service has its own policy
            assertFalse(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("aussie:admin")));
        }

        @Test
        @DisplayName("Should fall back to default policy when service has no policy")
        void shouldFallBackToDefaultPolicy() {
            var service = createServiceWithPolicy(null);

            // Default policy requires aussie:admin
            assertTrue(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("aussie:admin")));
            assertFalse(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("other-claim")));
        }

        @Test
        @DisplayName("Should fall back to default policy when service policy is empty")
        void shouldFallBackWhenPolicyEmpty() {
            var service = createServiceWithPolicy(ServicePermissionPolicy.empty());

            // Default policy requires aussie:admin
            assertTrue(authService.isAuthorizedForService(service, Permission.CONFIG_READ, Set.of("aussie:admin")));
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
