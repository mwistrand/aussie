package aussie.core.service.auth;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.auth.Permissions;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.service.ServiceRegistration;

/**
 * Service for evaluating service-level authorization.
 *
 * <p>
 * This service checks whether a set of permissions is authorized to perform
 * a specific operation on a service, based on the service's permission policy.
 *
 * <p>
 * Authorization logic:
 * <ol>
 * <li>If permissions contain wildcard (*), always allow</li>
 * <li>If service has no permission policy, fall back to global policy</li>
 * <li>Check if any permission matches the allowed permissions for the operation</li>
 * </ol>
 */
@ApplicationScoped
public class ServiceAuthorizationService {

    private final DefaultPermissionPolicy defaultPolicy;

    @Inject
    public ServiceAuthorizationService(DefaultPermissionPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    /**
     * Check authorization for an already-loaded service.
     *
     * @param service     the service to check
     * @param operation   the operation to perform
     * @param permissions the permissions from the authenticated principal
     * @return true if authorized, false otherwise
     */
    public boolean isAuthorizedForService(ServiceRegistration service, String operation, Set<String> permissions) {
        // Wildcard permission bypasses all checks
        if (permissions != null && permissions.contains(Permissions.ALL)) {
            return true;
        }

        if (permissions == null || permissions.isEmpty()) {
            return false;
        }

        // Get the service's permission policy, or use default
        var policy = service.permissionPolicy()
                .filter(ServicePermissionPolicy::hasPermissions)
                .orElseGet(defaultPolicy::getPolicy);

        return policy.isAllowed(operation, permissions);
    }

    /**
     * Check if a principal can create a new service.
     * Uses the global default policy since the service doesn't exist yet.
     *
     * @param permissions the permissions from the authenticated principal
     * @return true if authorized to create services, false otherwise
     */
    public boolean canCreateService(Set<String> permissions) {
        if (permissions != null && permissions.contains(Permissions.ALL)) {
            return true;
        }

        return defaultPolicy.getPolicy().isAllowed(Permissions.CONFIG_CREATE, permissions);
    }
}
