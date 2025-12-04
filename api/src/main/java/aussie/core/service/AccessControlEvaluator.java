package aussie.core.service;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceAccessConfig;
import aussie.core.model.SourceIdentifier;

@ApplicationScoped
public class AccessControlEvaluator {

    private final AccessControlConfig config;

    @Inject
    public AccessControlEvaluator(AccessControlConfig config) {
        this.config = config;
    }

    public boolean isAllowed(
            SourceIdentifier source, EndpointConfig endpoint, Optional<ServiceAccessConfig> serviceConfig) {

        // Public endpoints are always accessible
        if (endpoint.visibility() == EndpointVisibility.PUBLIC) {
            return true;
        }

        // For private endpoints, check if source is in allowed list
        return isSourceAllowed(source, serviceConfig);
    }

    private boolean isSourceAllowed(SourceIdentifier source, Optional<ServiceAccessConfig> serviceConfig) {
        // If service has specific restrictions, use those (subset of global)
        if (serviceConfig.isPresent() && serviceConfig.get().hasRestrictions()) {
            return isSourceInAllowedList(source, serviceConfig.get());
        }

        // Otherwise, use global access control configuration
        return isSourceInGlobalAllowedList(source);
    }

    private boolean isSourceInAllowedList(SourceIdentifier source, ServiceAccessConfig accessConfig) {
        // Check IPs
        if (accessConfig.allowedIps().isPresent()) {
            if (matchesIp(source.ipAddress(), accessConfig.allowedIps().get())) {
                return true;
            }
        }

        // Check domains
        if (source.host().isPresent()) {
            if (accessConfig.allowedDomains().isPresent()) {
                if (matchesDomain(
                        source.host().get(), accessConfig.allowedDomains().get())) {
                    return true;
                }
            }

            // Check subdomains
            if (accessConfig.allowedSubdomains().isPresent()) {
                if (matchesSubdomain(
                        source.host().get(), accessConfig.allowedSubdomains().get())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isSourceInGlobalAllowedList(SourceIdentifier source) {
        // Check IPs
        if (config.allowedIps().isPresent()) {
            if (matchesIp(source.ipAddress(), config.allowedIps().get())) {
                return true;
            }
        }

        // Check domains
        if (source.host().isPresent()) {
            if (config.allowedDomains().isPresent()) {
                if (matchesDomain(source.host().get(), config.allowedDomains().get())) {
                    return true;
                }
            }

            // Check subdomains
            if (config.allowedSubdomains().isPresent()) {
                if (matchesSubdomain(
                        source.host().get(), config.allowedSubdomains().get())) {
                    return true;
                }
            }
        }

        return false;
    }

    boolean matchesIp(String sourceIp, List<String> allowedPatterns) {
        for (var pattern : allowedPatterns) {
            if (pattern.contains("/")) {
                // CIDR notation
                if (matchesCidr(sourceIp, pattern)) {
                    return true;
                }
            } else {
                // Exact match
                if (pattern.equals(sourceIp)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesCidr(String sourceIp, String cidr) {
        try {
            var parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            var networkAddress = InetAddress.getByName(parts[0]);
            var prefixLength = Integer.parseInt(parts[1]);
            var sourceAddress = InetAddress.getByName(sourceIp);

            var networkBytes = networkAddress.getAddress();
            var sourceBytes = sourceAddress.getAddress();

            if (networkBytes.length != sourceBytes.length) {
                return false;
            }

            var fullBytes = prefixLength / 8;
            var remainingBits = prefixLength % 8;

            // Compare full bytes
            for (var i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != sourceBytes[i]) {
                    return false;
                }
            }

            // Compare remaining bits
            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                var mask = (byte) (0xFF << (8 - remainingBits));
                if ((networkBytes[fullBytes] & mask) != (sourceBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean matchesDomain(String sourceHost, List<String> allowedDomains) {
        var lowerSourceHost = sourceHost.toLowerCase();
        for (var domain : allowedDomains) {
            if (lowerSourceHost.equals(domain.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    boolean matchesSubdomain(String sourceHost, List<String> allowedSubdomains) {
        var lowerSourceHost = sourceHost.toLowerCase();
        for (var pattern : allowedSubdomains) {
            var lowerPattern = pattern.toLowerCase();

            // Handle *.example.com pattern
            if (lowerPattern.startsWith("*.")) {
                var suffix = lowerPattern.substring(1); // .example.com
                if (lowerSourceHost.endsWith(suffix) && !lowerSourceHost.equals(suffix.substring(1))) {
                    return true;
                }
            } else if (lowerSourceHost.equals(lowerPattern)) {
                return true;
            }
        }
        return false;
    }
}
