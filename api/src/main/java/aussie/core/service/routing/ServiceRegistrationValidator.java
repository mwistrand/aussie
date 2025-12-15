package aussie.core.service.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.common.ValidationResult;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;

/**
 * Validate service registrations against gateway policies.
 */
@ApplicationScoped
public class ServiceRegistrationValidator {

    private final GatewaySecurityConfig securityConfig;

    @Inject
    public ServiceRegistrationValidator(GatewaySecurityConfig securityConfig) {
        this.securityConfig = securityConfig;
    }

    /**
     * Validate a service registration against gateway security policies.
     *
     * <p>Note: Basic field validation (null checks, format validation) is handled by
     * the domain model constructors. This validator only checks policy-level constraints.
     *
     * @param registration the service registration to validate
     * @return ValidationResult.valid() if valid, or ValidationResult.Invalid with reason if not
     */
    public ValidationResult validate(ServiceRegistration registration) {
        // Check gateway guardrail for public default visibility
        if (EndpointVisibility.PUBLIC.equals(registration.defaultVisibility())
                && !securityConfig.publicDefaultVisibilityEnabled()) {
            return ValidationResult.invalid(
                    "PUBLIC default visibility is not allowed by gateway policy. "
                            + "Set defaultVisibility to PRIVATE or contact your gateway administrator.",
                    403);
        }

        // Note: VisibilityRule self-validates in constructor (pattern cannot be null/blank)
        // Future policy checks can be added here

        return ValidationResult.valid();
    }
}
