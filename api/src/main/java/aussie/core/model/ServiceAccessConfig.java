package aussie.core.model;

import java.util.List;
import java.util.Optional;

/**
 * Service-specific access configuration that can restrict private endpoint access
 * to a subset of the globally configured allowed sources.
 */
public record ServiceAccessConfig(
        Optional<List<String>> allowedIps,
        Optional<List<String>> allowedDomains,
        Optional<List<String>> allowedSubdomains) {
    public ServiceAccessConfig {
        if (allowedIps == null) {
            allowedIps = Optional.empty();
        }
        if (allowedDomains == null) {
            allowedDomains = Optional.empty();
        }
        if (allowedSubdomains == null) {
            allowedSubdomains = Optional.empty();
        }
    }

    public static ServiceAccessConfig empty() {
        return new ServiceAccessConfig(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public boolean hasRestrictions() {
        return allowedIps.isPresent() || allowedDomains.isPresent() || allowedSubdomains.isPresent();
    }
}
