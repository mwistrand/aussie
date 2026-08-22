package aussie.core.service.routing;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.config.RateLimitingConfig;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.auth.AccessControlConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.common.ValidationResult;
import aussie.core.model.ratelimit.ServiceWebSocketRateLimitConfig.RateLimitValues;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.common.IpNetwork;

/**
 * Validate service registrations against gateway policies.
 */
@ApplicationScoped
public class ServiceRegistrationValidator {

    private final GatewaySecurityConfig securityConfig;
    private final RateLimitingConfig rateLimitingConfig;
    private final ResiliencyConfig.HttpConfig httpConfig;
    private final List<IpNetwork> globalAllowedNetworks;

    @Inject
    public ServiceRegistrationValidator(
            GatewaySecurityConfig securityConfig,
            RateLimitingConfig rateLimitingConfig,
            ResiliencyConfig resiliencyConfig,
            AccessControlConfig accessControlConfig) {
        this.securityConfig = securityConfig;
        this.rateLimitingConfig = rateLimitingConfig;
        this.httpConfig = resiliencyConfig.http();
        this.globalAllowedNetworks = accessControlConfig.allowedIps().orElse(List.of()).stream()
                .map(IpNetwork::parse)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Convenience constructor for isolated tests whose focus is unrelated to access policy.
     * Production CDI always uses the injected constructor with the real global policy.
     */
    public ServiceRegistrationValidator(
            GatewaySecurityConfig securityConfig,
            RateLimitingConfig rateLimitingConfig,
            ResiliencyConfig resiliencyConfig) {
        this(securityConfig, rateLimitingConfig, resiliencyConfig, unrestrictedAccessConfig());
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
        final var upstreamResult = validateUpstreamHost(registration);
        if (upstreamResult.isInvalid()) {
            return upstreamResult;
        }

        final var accessResult = validateAccessControl(registration);
        if (accessResult.isInvalid()) {
            return accessResult;
        }

        // Check gateway guardrail for public default visibility
        if (EndpointVisibility.PUBLIC.equals(registration.defaultVisibility())
                && !securityConfig.publicDefaultVisibilityEnabled()) {
            return ValidationResult.invalid(
                    "PUBLIC default visibility is not allowed by gateway policy. "
                            + "Set defaultVisibility to PRIVATE or contact your gateway administrator.",
                    403);
        }

        // Check service-level rate limit windowSeconds against platform maximum
        final var windowSecondsResult = validateWindowSeconds(registration);
        if (windowSecondsResult.isInvalid()) {
            return windowSecondsResult;
        }

        // Check service-level rate limit requestsPerWindow against platform maximum
        final var requestsResult = validateRequestsPerWindow(registration);
        if (requestsResult.isInvalid()) {
            return requestsResult;
        }

        // Check endpoint-level rate limits against platform maximums
        final var endpointResult = validateEndpointRateLimits(registration);
        if (endpointResult.isInvalid()) {
            return endpointResult;
        }

        // Check service-level and endpoint-level timeouts against platform maximum
        final var timeoutResult = validateTimeouts(registration);
        if (timeoutResult.isInvalid()) {
            return timeoutResult;
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateAccessControl(ServiceRegistration registration) {
        if (registration.accessConfig().isEmpty()) {
            return ValidationResult.valid();
        }

        final var servicePolicy = registration.accessConfig().get();
        if (servicePolicy.allowedDomains().filter(values -> !values.isEmpty()).isPresent()
                || servicePolicy
                        .allowedSubdomains()
                        .filter(values -> !values.isEmpty())
                        .isPresent()) {
            return ValidationResult.invalid(
                    "Domain-based caller access control is not supported; use allowedIps with trusted client IPs.",
                    400);
        }

        final var patterns = servicePolicy.allowedIps().orElse(List.of());
        for (final var pattern : patterns) {
            final var parsed = IpNetwork.parse(pattern);
            if (parsed.isEmpty()) {
                return ValidationResult.invalid("Invalid service access-control IP/CIDR: " + pattern, 400);
            }
            if (globalAllowedNetworks.stream().noneMatch(global -> global.contains(parsed.get()))) {
                return ValidationResult.invalid(
                        "Service access-control range is outside the global allowed IP boundary: " + pattern, 400);
            }
        }
        return ValidationResult.valid();
    }

    private static AccessControlConfig unrestrictedAccessConfig() {
        return new AccessControlConfig() {
            @Override
            public Optional<List<String>> allowedIps() {
                return Optional.of(List.of("0.0.0.0/0", "::/0"));
            }

            @Override
            public Optional<List<String>> allowedDomains() {
                return Optional.empty();
            }

            @Override
            public Optional<List<String>> allowedSubdomains() {
                return Optional.empty();
            }
        };
    }

    private ValidationResult validateUpstreamHost(ServiceRegistration registration) {
        final var host = normalizeHost(registration.baseUrl().getHost());
        final var allowedHosts = securityConfig.allowedUpstreamHosts();
        if (host == null
                || allowedHosts.isEmpty()
                || allowedHosts.get().stream().noneMatch(entry -> matchesHost(host, entry))) {
            return ValidationResult.invalid(
                    "Upstream host is not allowed by gateway egress policy: "
                            + registration.baseUrl().getHost(),
                    400);
        }
        if (UpstreamAddressPolicy.isBlocked(host, securityConfig.allowPrivateUpstreams())) {
            return ValidationResult.invalid(
                    "Upstream address is not allowed by gateway egress policy: "
                            + registration.baseUrl().getHost(),
                    400);
        }
        return ValidationResult.valid();
    }

    private static boolean matchesHost(String host, String configuredEntry) {
        final var pattern = normalizeHost(configuredEntry);
        if (pattern == null || "*".equals(pattern)) {
            return false;
        }
        if (pattern.startsWith("*.")) {
            final var suffix = pattern.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(pattern);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        var normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private ValidationResult validateWindowSeconds(ServiceRegistration registration) {
        final var platformMax = rateLimitingConfig.platformMaxWindowSeconds();
        final var rateLimitConfig = registration.rateLimitConfig();

        if (rateLimitConfig.isEmpty()) {
            return ValidationResult.valid();
        }

        final var config = rateLimitConfig.get();

        // Check HTTP windowSeconds
        final var httpResult = checkWindowSeconds("HTTP", config.windowSeconds(), platformMax);
        if (httpResult.isInvalid()) {
            return httpResult;
        }

        // Check WebSocket connection windowSeconds
        final var connWindowSeconds =
                config.websocket().flatMap(ws -> ws.connection()).flatMap(RateLimitValues::windowSeconds);
        final var connResult = checkWindowSeconds("WebSocket connection", connWindowSeconds, platformMax);
        if (connResult.isInvalid()) {
            return connResult;
        }

        // Check WebSocket message windowSeconds
        final var msgWindowSeconds =
                config.websocket().flatMap(ws -> ws.message()).flatMap(RateLimitValues::windowSeconds);
        return checkWindowSeconds("WebSocket message", msgWindowSeconds, platformMax);
    }

    private ValidationResult checkWindowSeconds(String context, Optional<Long> windowSeconds, long platformMax) {
        if (windowSeconds.isPresent() && windowSeconds.get() > platformMax) {
            return ValidationResult.invalid(
                    "%s windowSeconds %d exceeds the platform maximum of %d seconds."
                            .formatted(context, windowSeconds.get(), platformMax),
                    400);
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateRequestsPerWindow(ServiceRegistration registration) {
        final var platformMax = rateLimitingConfig.platformMaxRequestsPerWindow();
        final var rateLimitConfig = registration.rateLimitConfig();

        if (rateLimitConfig.isEmpty()) {
            return ValidationResult.valid();
        }

        final var config = rateLimitConfig.get();

        // Check HTTP requestsPerWindow
        final var httpResult = checkRequestsPerWindow("HTTP", config.requestsPerWindow(), platformMax);
        if (httpResult.isInvalid()) {
            return httpResult;
        }

        // Check HTTP burstCapacity
        final var burstResult = checkRequestsPerWindow("HTTP burstCapacity", config.burstCapacity(), platformMax);
        if (burstResult.isInvalid()) {
            return burstResult;
        }

        // Check WebSocket connection requestsPerWindow and burstCapacity
        final var connRequests =
                config.websocket().flatMap(ws -> ws.connection()).flatMap(RateLimitValues::requestsPerWindow);
        final var connResult = checkRequestsPerWindow("WebSocket connection", connRequests, platformMax);
        if (connResult.isInvalid()) {
            return connResult;
        }

        final var connBurst = config.websocket().flatMap(ws -> ws.connection()).flatMap(RateLimitValues::burstCapacity);
        final var connBurstResult =
                checkRequestsPerWindow("WebSocket connection burstCapacity", connBurst, platformMax);
        if (connBurstResult.isInvalid()) {
            return connBurstResult;
        }

        // Check WebSocket message requestsPerWindow and burstCapacity
        final var msgRequests =
                config.websocket().flatMap(ws -> ws.message()).flatMap(RateLimitValues::requestsPerWindow);
        final var msgResult = checkRequestsPerWindow("WebSocket message", msgRequests, platformMax);
        if (msgResult.isInvalid()) {
            return msgResult;
        }

        final var msgBurst = config.websocket().flatMap(ws -> ws.message()).flatMap(RateLimitValues::burstCapacity);
        return checkRequestsPerWindow("WebSocket message burstCapacity", msgBurst, platformMax);
    }

    private ValidationResult checkRequestsPerWindow(
            String context, Optional<Long> requestsPerWindow, long platformMax) {
        if (requestsPerWindow.isPresent() && requestsPerWindow.get() > platformMax) {
            return ValidationResult.invalid(
                    "%s requestsPerWindow %d exceeds the platform maximum of %d."
                            .formatted(context, requestsPerWindow.get(), platformMax),
                    400);
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateEndpointRateLimits(ServiceRegistration registration) {
        final var maxRequests = rateLimitingConfig.platformMaxRequestsPerWindow();
        final var maxWindow = rateLimitingConfig.platformMaxWindowSeconds();

        for (final var endpoint : registration.endpoints()) {
            if (endpoint.rateLimitConfig().isEmpty()) {
                continue;
            }

            final var config = endpoint.rateLimitConfig().get();
            final var path = endpoint.path();

            final var requestsResult =
                    checkRequestsPerWindow("Endpoint '%s'".formatted(path), config.requestsPerWindow(), maxRequests);
            if (requestsResult.isInvalid()) {
                return requestsResult;
            }

            final var burstResult = checkRequestsPerWindow(
                    "Endpoint '%s' burstCapacity".formatted(path), config.burstCapacity(), maxRequests);
            if (burstResult.isInvalid()) {
                return burstResult;
            }

            final var windowResult =
                    checkWindowSeconds("Endpoint '%s'".formatted(path), config.windowSeconds(), maxWindow);
            if (windowResult.isInvalid()) {
                return windowResult;
            }
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateTimeouts(ServiceRegistration registration) {
        final var platformMax = httpConfig.maxRequestTimeout();

        // Check service-level timeout
        final var serviceTimeoutResult =
                checkTimeout("Service", registration.timeoutConfig().flatMap(tc -> tc.requestTimeout()), platformMax);
        if (serviceTimeoutResult.isInvalid()) {
            return serviceTimeoutResult;
        }

        // Check endpoint-level timeouts
        for (final var endpoint : registration.endpoints()) {
            if (endpoint.timeoutConfig().isEmpty()) {
                continue;
            }

            final var endpointTimeout = endpoint.timeoutConfig().get().requestTimeout();
            final var endpointResult =
                    checkTimeout("Endpoint '%s'".formatted(endpoint.path()), endpointTimeout, platformMax);
            if (endpointResult.isInvalid()) {
                return endpointResult;
            }
        }

        return ValidationResult.valid();
    }

    private ValidationResult checkTimeout(String context, Optional<Duration> timeout, Duration platformMax) {
        if (timeout.isPresent() && timeout.get().compareTo(platformMax) > 0) {
            return ValidationResult.invalid(
                    "%s requestTimeout %s exceeds the platform maximum of %s."
                            .formatted(context, timeout.get(), platformMax),
                    400);
        }
        return ValidationResult.valid();
    }
}
