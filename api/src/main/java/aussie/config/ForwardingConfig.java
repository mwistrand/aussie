package aussie.config;

import io.smallrye.config.WithDefault;

public interface ForwardingConfig {

    /**
     * When true, use RFC 7239 Forwarded header.
     * When false, use X-Forwarded-For, X-Forwarded-Host, X-Forwarded-Proto headers.
     */
    @WithDefault("true")
    boolean useRfc7239();
}
