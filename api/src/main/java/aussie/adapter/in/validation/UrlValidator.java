package aussie.adapter.in.validation;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import aussie.adapter.in.problem.GatewayProblem;

/**
 * Utility for validating URLs with SSRF protection.
 *
 * <p>Validates that URLs use safe schemes and do not point to internal
 * infrastructure, metadata endpoints, or other sensitive addresses.
 */
public final class UrlValidator {

    private UrlValidator() {}

    /**
     * Validate a URL for use as an upstream service base URL.
     *
     * <p>Rejects non-HTTP schemes, missing hosts, and known internal/metadata
     * IP addresses to prevent SSRF attacks.
     *
     * @param url       the URL string to validate
     * @param paramName the parameter name for error messages
     * @return the parsed and validated URI
     * @throws io.quarkiverse.resteasy.problem.HttpProblem if validation fails
     */
    public static URI validateServiceUrl(String url, String paramName) {
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.badRequest(paramName + " is not a valid URL");
        }

        final var scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw GatewayProblem.badRequest(paramName + " must use http or https scheme");
        }

        final var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw GatewayProblem.badRequest(paramName + " must have a valid host");
        }

        if (isBlockedHost(host)) {
            throw GatewayProblem.badRequest(
                    paramName + " must not point to a loopback, link-local, or metadata address");
        }

        return uri;
    }

    /**
     * Check if a host is blocked for SSRF protection.
     *
     * <p>Blocks:
     * <ul>
     *   <li>Loopback addresses (127.x.x.x, ::1, localhost)</li>
     *   <li>Link-local addresses (169.254.x.x) - includes cloud metadata endpoints</li>
     *   <li>Wildcard addresses (0.0.0.0, ::)</li>
     * </ul>
     *
     * <p>Note: Site-local addresses (10.x, 172.16-31.x, 192.168.x) are allowed
     * for internal service-to-service routing.
     *
     * @param host the hostname or IP address to check
     * @return true if the host is blocked
     */
    private static boolean isBlockedHost(String host) {
        if (host.equalsIgnoreCase("localhost") || host.equals("0.0.0.0") || host.equals("::")) {
            return true;
        }

        try {
            final var addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
