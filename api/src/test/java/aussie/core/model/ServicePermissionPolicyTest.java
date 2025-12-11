package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServicePermissionPolicy")
class ServicePermissionPolicyTest {

    @Nested
    @DisplayName("isAllowed")
    class IsAllowedTests {

        @Test
        @DisplayName("Should allow when permission matches")
        void shouldAllowWhenPermissionMatches() {
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("admin", "readonly"))));

            assertTrue(policy.isAllowed("service.config.read", Set.of("admin")));
            assertTrue(policy.isAllowed("service.config.read", Set.of("readonly")));
            assertTrue(policy.isAllowed("service.config.read", Set.of("other", "admin")));
        }

        @Test
        @DisplayName("Should deny when no permission matches")
        void shouldDenyWhenNoPermissionMatches() {
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("admin"))));

            assertFalse(policy.isAllowed("service.config.read", Set.of("readonly")));
            assertFalse(policy.isAllowed("service.config.read", Set.of("other", "different")));
        }

        @Test
        @DisplayName("Should deny when operation not defined in policy")
        void shouldDenyWhenOperationNotDefined() {
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("admin"))));

            assertFalse(policy.isAllowed("service.config.update", Set.of("admin")));
        }

        @Test
        @DisplayName("Should deny when permissions are null")
        void shouldDenyWhenPermissionsNull() {
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("admin"))));

            assertFalse(policy.isAllowed("service.config.read", null));
        }

        @Test
        @DisplayName("Should deny when permissions are empty")
        void shouldDenyWhenPermissionsEmpty() {
            var policy = new ServicePermissionPolicy(
                    Map.of("service.config.read", new OperationPermission(Set.of("admin"))));

            assertFalse(policy.isAllowed("service.config.read", Set.of()));
        }
    }

    @Nested
    @DisplayName("empty policy")
    class EmptyPolicyTests {

        @Test
        @DisplayName("Empty policy should deny all operations")
        void emptyPolicyShouldDenyAll() {
            var policy = ServicePermissionPolicy.empty();

            assertFalse(policy.isAllowed("service.config.read", Set.of("admin")));
            assertFalse(policy.hasPermissions());
        }

        @Test
        @DisplayName("Null permissions map should create empty policy")
        void nullPermissionsShouldCreateEmptyPolicy() {
            var policy = new ServicePermissionPolicy(null);

            assertFalse(policy.hasPermissions());
        }
    }
}
