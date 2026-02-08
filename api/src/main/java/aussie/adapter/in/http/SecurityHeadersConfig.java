package aussie.adapter.in.http;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for HTTP security response headers.
 *
 * <p>Controls which security headers are added to all responses.
 * Headers follow OWASP recommendations for secure defaults.
 */
@ConfigMapping(prefix = "aussie.gateway.security-headers")
public interface SecurityHeadersConfig {

    /**
     * Enable security headers.
     *
     * @return true if security headers should be added (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * X-Content-Type-Options header value.
     * Prevents MIME type sniffing.
     *
     * @return header value (default: nosniff)
     */
    @WithDefault("nosniff")
    String contentTypeOptions();

    /**
     * X-Frame-Options header value.
     * Controls whether the response can be displayed in a frame.
     *
     * @return header value (default: DENY)
     */
    @WithDefault("DENY")
    String frameOptions();

    /**
     * Content-Security-Policy header value.
     * Controls resources the user agent is allowed to load.
     *
     * @return CSP value (default: default-src 'none')
     */
    @WithDefault("default-src 'none'")
    String contentSecurityPolicy();

    /**
     * Referrer-Policy header value.
     * Controls how much referrer information is sent.
     *
     * @return header value (default: strict-origin-when-cross-origin)
     */
    @WithDefault("strict-origin-when-cross-origin")
    String referrerPolicy();

    /**
     * X-Permitted-Cross-Domain-Policies header value.
     * Controls cross-domain policy files (e.g., for Flash/PDF).
     *
     * @return header value (default: none)
     */
    @WithDefault("none")
    String permittedCrossDomainPolicies();

    /**
     * Strict-Transport-Security header value.
     * Enforces HTTPS connections.
     *
     * <p>Not set by default. Only enable when the gateway is behind
     * TLS termination. Incorrect HSTS configuration can lock
     * browsers out of your site.
     *
     * @return optional HSTS value
     */
    Optional<String> strictTransportSecurity();

    /**
     * Permissions-Policy header value.
     * Controls which browser features can be used.
     *
     * @return optional value
     */
    Optional<String> permissionsPolicy();
}
