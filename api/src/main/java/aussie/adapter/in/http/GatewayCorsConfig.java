package aussie.adapter.in.http;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Global CORS configuration mapping.
 *
 * <p>These settings serve as defaults for all services. Individual services
 * can override these settings in their registration configuration.
 */
@ConfigMapping(prefix = "aussie.gateway.cors")
public interface GatewayCorsConfig {

    /**
     * Enable CORS handling.
     *
     * @return true if CORS is enabled (default: false)
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Allowed origins for CORS requests.
     *
     * <p>Use an explicit list of origins. Wildcard origins are only safe when
     * credentials are disabled and are not the default.
     *
     * @return List of allowed origins (default: a non-origin deny sentinel)
     */
    @WithDefault("__deny_all__")
    List<String> allowedOrigins();

    /**
     * Allowed HTTP methods.
     *
     * @return Set of allowed methods (default: common REST methods)
     */
    @WithDefault("GET,POST,PUT,DELETE,PATCH,OPTIONS,HEAD")
    Set<String> allowedMethods();

    /**
     * Allowed request headers.
     *
     * <p>Use "*" to allow all headers.
     *
     * @return Set of allowed headers
     */
    @WithDefault("Content-Type,Authorization,X-Requested-With,Accept,Origin,X-CSRF-Token")
    Set<String> allowedHeaders();

    /**
     * Headers to expose to the client.
     *
     * @return Optional set of exposed headers
     */
    Optional<Set<String>> exposedHeaders();

    /**
     * Allow credentials (cookies, authorization headers).
     *
     * <p>Note: When true, allowedOrigins cannot be "*" for security reasons.
     *
     * @return true if credentials are allowed (default: false)
     */
    @WithDefault("false")
    boolean allowCredentials();

    /**
     * Max age for preflight cache in seconds.
     *
     * @return Max age in seconds (default: 3600)
     */
    @WithDefault("3600")
    Optional<Long> maxAge();
}
