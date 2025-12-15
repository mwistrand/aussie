package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Permission;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.service.auth.*;

@DisplayName("DefaultPermissionPolicy")
class DefaultPermissionPolicyTest {

    private DefaultPermissionPolicy defaultPolicy;

    @BeforeEach
    void setUp() {
        defaultPolicy = new DefaultPermissionPolicy();
    }

    @Test
    @DisplayName("Should return a non-null policy")
    void shouldReturnNonNullPolicy() {
        assertNotNull(defaultPolicy.getPolicy());
    }

    @Test
    @DisplayName("Should return a policy with permissions defined")
    void shouldReturnPolicyWithPermissions() {
        ServicePermissionPolicy policy = defaultPolicy.getPolicy();
        assertTrue(policy.hasPermissions());
    }

    @Nested
    @DisplayName("Default policy authorization")
    class DefaultPolicyAuthorizationTests {

        @Test
        @DisplayName("Should allow aussie:admin permission for service.config.create")
        void shouldAllowAdminPermissionForConfigCreate() {
            assertTrue(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_CREATE, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission for service.config.read")
        void shouldAllowAdminPermissionForConfigRead() {
            assertTrue(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_READ, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission for service.config.update")
        void shouldAllowAdminPermissionForConfigUpdate() {
            assertTrue(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_UPDATE, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission for service.config.delete")
        void shouldAllowAdminPermissionForConfigDelete() {
            assertTrue(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_DELETE, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission for service.permissions.read")
        void shouldAllowAdminPermissionForPermissionsRead() {
            assertTrue(
                    defaultPolicy.getPolicy().isAllowed(Permission.PERMISSIONS_READ, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should allow aussie:admin permission for service.permissions.write")
        void shouldAllowAdminPermissionForPermissionsWrite() {
            assertTrue(
                    defaultPolicy.getPolicy().isAllowed(Permission.PERMISSIONS_WRITE, Set.of(Permission.ADMIN_CLAIM)));
        }

        @Test
        @DisplayName("Should deny non-admin permissions for all operations")
        void shouldDenyNonAdminPermissions() {
            Set<String> nonAdminPermissions = Set.of("user", "readonly", "other-service.admin");

            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_CREATE, nonAdminPermissions));
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_READ, nonAdminPermissions));
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_UPDATE, nonAdminPermissions));
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_DELETE, nonAdminPermissions));
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.PERMISSIONS_READ, nonAdminPermissions));
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.PERMISSIONS_WRITE, nonAdminPermissions));
        }

        @Test
        @DisplayName("Should deny empty permissions")
        void shouldDenyEmptyPermissions() {
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_READ, Set.of()));
        }

        @Test
        @DisplayName("Should deny null permissions")
        void shouldDenyNullPermissions() {
            assertFalse(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_READ, null));
        }

        @Test
        @DisplayName("Should allow aussie:admin among other permissions")
        void shouldAllowAdminAmongOtherPermissions() {
            Set<String> mixedPermissions = Set.of("user", Permission.ADMIN_CLAIM, "reader");
            assertTrue(defaultPolicy.getPolicy().isAllowed(Permission.CONFIG_UPDATE, mixedPermissions));
        }
    }
}
