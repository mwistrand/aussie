package aussie.adapter.in.dto;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import aussie.adapter.in.validation.UrlValidator;
import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.sampling.ServiceSamplingConfig;
import aussie.core.model.service.ServiceRegistration;

/**
 * DTO for service registration requests.
 *
 * @param version             optimistic locking version (1 for new services)
 * @param serviceId           unique service identifier
 * @param displayName         human-readable service name
 * @param baseUrl             upstream service base URL
 * @param routePrefix         URL prefix for routing to this service
 * @param defaultVisibility   default visibility for endpoints (PUBLIC or PRIVATE)
 * @param defaultAuthRequired default authentication requirement for endpoints
 * @param visibilityRules     visibility rules for path patterns
 * @param endpoints           endpoint configurations
 * @param accessConfig        service access configuration
 * @param cors                CORS configuration
 * @param permissionPolicy    permission policy for authorization
 * @param rateLimitConfig     service-level rate limiting configuration
 * @param samplingConfig      service-level OTel sampling configuration
 */
public record ServiceRegistrationRequest(
        @Min(value = 1, message = "version must be at least 1") Long version,
        @NotBlank(message = "serviceId is required")
                @Size(max = 64, message = "serviceId must be 64 characters or less")
                String serviceId,
        @Size(max = 255, message = "displayName must be 255 characters or less") String displayName,
        @NotBlank(message = "baseUrl is required") String baseUrl,
        String routePrefix,
        @Pattern(regexp = "^(PUBLIC|PRIVATE)$", message = "defaultVisibility must be PUBLIC or PRIVATE")
                String defaultVisibility,
        Boolean defaultAuthRequired,
        List<@Valid VisibilityRuleDto> visibilityRules,
        List<@Valid EndpointConfigDto> endpoints,
        @Valid ServiceAccessConfigDto accessConfig,
        @Valid CorsConfigDto cors,
        @Valid ServicePermissionPolicyDto permissionPolicy,
        @Valid ServiceRateLimitConfigDto rateLimitConfig,
        @Valid ServiceSamplingConfigDto samplingConfig) {
    /**
     * Convert this DTO to a {@link ServiceRegistration} domain model.
     *
     * <p>Validates {@code baseUrl} against SSRF blocklists during conversion.
     *
     * @throws io.quarkiverse.resteasy.problem.HttpProblem if baseUrl fails validation
     */
    public ServiceRegistration toModel() {
        var defaultVis = defaultVisibility != null
                ? EndpointVisibility.valueOf(defaultVisibility.toUpperCase())
                : EndpointVisibility.PRIVATE;

        var defaultAuth = defaultAuthRequired != null ? defaultAuthRequired : true;

        var visibilityRuleModels = visibilityRules != null
                ? visibilityRules.stream().map(VisibilityRuleDto::toModel).toList()
                : List.<VisibilityRule>of();

        var endpointModels = endpoints != null
                ? endpoints.stream().map(e -> e.toModel(defaultAuth)).toList()
                : List.<aussie.core.model.routing.EndpointConfig>of();

        var accessConfigModel = accessConfig != null
                ? Optional.of(accessConfig.toModel())
                : Optional.<aussie.core.model.auth.ServiceAccessConfig>empty();

        var corsConfigModel = cors != null ? Optional.of(cors.toModel()) : Optional.<CorsConfig>empty();

        var permissionPolicyModel = permissionPolicy != null
                ? Optional.of(permissionPolicy.toModel())
                : Optional.<aussie.core.model.auth.ServicePermissionPolicy>empty();

        var rateLimitConfigModel = rateLimitConfig != null
                ? Optional.of(rateLimitConfig.toModel())
                : Optional.<aussie.core.model.ratelimit.ServiceRateLimitConfig>empty();

        var samplingConfigModel = samplingConfig != null
                ? Optional.of(samplingConfig.toModel())
                : Optional.<ServiceSamplingConfig>empty();

        return new ServiceRegistration(
                serviceId,
                displayName != null ? displayName : serviceId,
                UrlValidator.validateServiceUrl(baseUrl, "baseUrl"),
                routePrefix,
                defaultVis,
                defaultAuth,
                visibilityRuleModels,
                endpointModels,
                accessConfigModel,
                corsConfigModel,
                permissionPolicyModel,
                rateLimitConfigModel,
                samplingConfigModel,
                version == null ? 1L : version); // New registrations start at version 1
    }
}
