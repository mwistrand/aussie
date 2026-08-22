package aussie.core.model.auth;

import java.util.List;
import java.util.Optional;

public interface AccessControlConfig {

    /**
     * Global list of allowed IP addresses or CIDR ranges for private endpoint access.
     * Example: 10.0.0.0/8,192.168.0.0/16
     */
    Optional<List<String>> allowedIps();

    /**
     * Legacy compatibility setting. Host names are request routing metadata and are
     * never used to authorize callers.
     */
    Optional<List<String>> allowedDomains();

    /**
     * Legacy compatibility setting. Host names are request routing metadata and are
     * never used to authorize callers.
     */
    Optional<List<String>> allowedSubdomains();
}
