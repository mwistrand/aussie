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
 * The default policy requires the "aussie:admin" claim for all operations.
 * This ensures that new services are secure by default.
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
        // Default: Only "aussie:admin" claim can perform any operation
        var adminOnly = new OperationPermission(Set.of(Permission.ADMIN_CLAIM.value()));

        this.defaultPolicy = new ServicePermissionPolicy(Map.of(
                Permission.CONFIG_CREATE.value(), adminOnly,
                Permission.CONFIG_READ.value(), adminOnly,
                Permission.CONFIG_UPDATE.value(), adminOnly,
                Permission.CONFIG_DELETE.value(), adminOnly,
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
