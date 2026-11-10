package aussie.core.config;

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

    /** Maximum HTTP/1 initial line (request target) size in bytes. Default: 4 KB. */
    @WithDefault("4096")
    int maxInitialLineLength();

    /** Maximum form attribute size in bytes. Default: 8 KB. */
    @WithDefault("8192")
    int maxFormAttributeSize();

    /** Maximum bytes buffered while decoding a form. Default: 1 KB. */
    @WithDefault("1024")
    int maxFormBufferedBytes();

    /** Maximum number of form fields. Default: 256. */
    @WithDefault("256")
    int maxFormFields();

    /** Maximum number of request parameters. Default: 1000. */
    @WithDefault("1000")
    int maxParameters();
}
