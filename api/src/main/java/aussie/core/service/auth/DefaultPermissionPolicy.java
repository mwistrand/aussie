package aussie.core.service.auth;

import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import aussie.core.model.auth.OperationPermission;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.ServicePermissionPolicy;

/**
 * Provides the default permission policy for services without explicit
 * policies.
 *
 * <p>
 * Config operations ({@code service.config.*}) accept either the
 * {@code aussie:admin} claim or the corresponding un-scoped permission
 * (e.g., {@code service.config.read}). Permission policy operations
 * ({@code service.permissions.*}) require {@code aussie:admin}.
 *
 * <p>
 * Organizations can customize this by:
 * <ol>
 * <li>Setting explicit permission policies on individual services</li>
 * <li>Eventually: configuring the default policy via application
 * properties</li>
 * </ol>
 */
@ApplicationScoped
public class DefaultPermissionPolicy {

    private final ServicePermissionPolicy defaultPolicy;

    public DefaultPermissionPolicy() {
        // Default: "aussie:admin" claim or un-scoped permission can perform operations
        var adminOnly = new OperationPermission(Set.of(Permission.ADMIN_CLAIM.value()));

        var adminOrCreate = new OperationPermission(
                Set.of(Permission.ADMIN_CLAIM.value(), Permission.SERVICE_CONFIG_CREATE.value()));
        var adminOrRead =
                new OperationPermission(Set.of(Permission.ADMIN_CLAIM.value(), Permission.SERVICE_CONFIG_READ.value()));
        var adminOrUpdate = new OperationPermission(
                Set.of(Permission.ADMIN_CLAIM.value(), Permission.SERVICE_CONFIG_UPDATE.value()));
        var adminOrDelete = new OperationPermission(
                Set.of(Permission.ADMIN_CLAIM.value(), Permission.SERVICE_CONFIG_DELETE.value()));

        this.defaultPolicy = new ServicePermissionPolicy(Map.of(
                Permission.CONFIG_CREATE.value(), adminOrCreate,
                Permission.CONFIG_READ.value(), adminOrRead,
                Permission.CONFIG_UPDATE.value(), adminOrUpdate,
                Permission.CONFIG_DELETE.value(), adminOrDelete,
                Permission.PERMISSIONS_READ.value(), adminOnly,
                Permission.PERMISSIONS_WRITE.value(), adminOnly));
    }

    /**
     * Return the default permission policy.
     */
    public ServicePermissionPolicy getPolicy() {
        return defaultPolicy;
    }
}
