package aussie.core.service;

import java.util.List;
import java.util.Optional;

public interface AccessControlConfig {

    /**
     * Global list of allowed IP addresses or CIDR ranges for private endpoint access.
     * Example: 10.0.0.0/8,192.168.0.0/16
     */
    Optional<List<String>> allowedIps();

    /**
     * Global list of allowed domains for private endpoint access.
     * Example: internal.example.com
     */
    Optional<List<String>> allowedDomains();

    /**
     * Global list of allowed subdomain patterns for private endpoint access.
     * Supports wildcard patterns like *.internal.example.com
     */
    Optional<List<String>> allowedSubdomains();
}
