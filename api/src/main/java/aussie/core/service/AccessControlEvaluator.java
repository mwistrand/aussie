package aussie.core.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.AccessControlConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RouteLookupResult;
import aussie.core.model.ServiceAccessConfig;
import aussie.core.model.SourceIdentifier;

@ApplicationScoped
public class AccessControlEvaluator {

    private final AccessControlConfig config;

    // Cache for parsed CIDR network addresses - computed once per unique CIDR
    // pattern
    private final Map<String, ParsedCidr> cidrCache = new ConcurrentHashMap<>();

    // Cache for parsed source IP addresses - computed once per unique IP string
    private final Map<String, byte[]> ipAddressCache = new ConcurrentHashMap<>();

    @Inject
    public AccessControlEvaluator(AccessControlConfig config) {
        this.config = config;
    }

    /**
     * Pre-parsed CIDR network for fast matching.
     */
    private record ParsedCidr(byte[] networkBytes, int prefixLength) {}

    public boolean isAllowed(
            SourceIdentifier source, RouteLookupResult route, Optional<ServiceAccessConfig> serviceConfig) {

        // Public endpoints are always accessible
        if (EndpointVisibility.PUBLIC.equals(route.visibility())) {
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
            // Get cached parsed CIDR or parse and cache it
            final var parsedCidr = cidrCache.computeIfAbsent(cidr, this::parseCidr);
            if (parsedCidr == null) {
                return false;
            }

            // Get cached parsed source IP or parse and cache it
            final var sourceBytes = ipAddressCache.computeIfAbsent(sourceIp, this::parseIpAddress);
            if (sourceBytes == null) {
                return false;
            }

            final var networkBytes = parsedCidr.networkBytes();
            final var prefixLength = parsedCidr.prefixLength();

            if (networkBytes.length != sourceBytes.length) {
                return false;
            }

            final var fullBytes = prefixLength / 8;
            final var remainingBits = prefixLength % 8;

            // Compare full bytes
            for (var i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != sourceBytes[i]) {
                    return false;
                }
            }

            // Compare remaining bits
            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                final var mask = (byte) (0xFF << (8 - remainingBits));
                if ((networkBytes[fullBytes] & mask) != (sourceBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses a CIDR pattern into network bytes and prefix length.
     * Returns null if the pattern is invalid.
     */
    private ParsedCidr parseCidr(String cidr) {
        try {
            final var parts = cidr.split("/");
            if (parts.length != 2) {
                return null;
            }
            final var networkAddress = InetAddress.getByName(parts[0]);
            final var prefixLength = Integer.parseInt(parts[1]);
            return new ParsedCidr(networkAddress.getAddress(), prefixLength);
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses an IP address string into bytes.
     * Returns null if the address is invalid.
     */
    private byte[] parseIpAddress(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
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
