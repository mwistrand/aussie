package aussie.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.GatewaySecurityConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.ValidationResult;

/**
 * Validates service registrations against gateway policies.
 */
@ApplicationScoped
public class ServiceRegistrationValidator {

    private final GatewaySecurityConfig securityConfig;

    @Inject
    public ServiceRegistrationValidator(GatewaySecurityConfig securityConfig) {
        this.securityConfig = securityConfig;
    }

    /**
     * Validates a service registration against gateway security policies.
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

        // Validate visibility rules patterns
        for (var rule : registration.visibilityRules()) {
            if (rule.pattern() == null || rule.pattern().isBlank()) {
                return ValidationResult.invalid("Visibility rule pattern cannot be empty", 400);
            }
        }

        return ValidationResult.valid();
    }
}
