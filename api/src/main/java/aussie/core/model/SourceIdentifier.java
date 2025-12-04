package aussie.core.model;

import java.util.Optional;

public record SourceIdentifier(String ipAddress, Optional<String> host, Optional<String> forwardedFor) {
    public SourceIdentifier {
        if (ipAddress == null) {
            ipAddress = "unknown";
        }
        if (host == null) {
            host = Optional.empty();
        }
        if (forwardedFor == null) {
            forwardedFor = Optional.empty();
        }
    }

    public static SourceIdentifier of(String ipAddress) {
        return new SourceIdentifier(ipAddress, Optional.empty(), Optional.empty());
    }

    public static SourceIdentifier of(String ipAddress, String host) {
        return new SourceIdentifier(ipAddress, Optional.ofNullable(host), Optional.empty());
    }
}
