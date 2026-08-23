package aussie.core.model.common;

import io.smallrye.config.WithDefault;

public interface LimitsConfig {

    /**
     * Maximum request body size in bytes. Default: 10 MB.
     */
    @WithDefault("10485760")
    long maxBodySize();

    /** Maximum upstream response body size in bytes. Default: 100 MB. */
    @WithDefault("104857600")
    long maxResponseBodySize();

    /**
     * Maximum size of a single header in bytes. Default: 8 KB.
     */
    @WithDefault("8192")
    int maxHeaderSize();

    /**
     * Maximum total size of all headers in bytes. Default: 32 KB.
     */
    @WithDefault("32768")
    int maxTotalHeadersSize();
}
