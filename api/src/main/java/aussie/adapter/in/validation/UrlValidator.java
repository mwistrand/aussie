package aussie.adapter.in.validation;

import java.net.URI;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.service.routing.UpstreamAddressPolicy;

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
     * @param url                   the URL string to validate
     * @param paramName             the parameter name for error messages
     * @param allowPrivateUpstreams whether to allow site-local (private) addresses
     * @return the parsed and validated URI
     * @throws io.quarkiverse.resteasy.problem.HttpProblem if validation fails
     */
    public static URI validateServiceUrl(String url, String paramName, boolean allowPrivateUpstreams) {
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

        if (UpstreamAddressPolicy.isBlocked(host, allowPrivateUpstreams)) {
            final var detail = allowPrivateUpstreams
                    ? paramName + " must not point to a loopback, link-local, or metadata address"
                    : paramName + " must not point to a loopback, link-local, site-local, or metadata address";
            throw GatewayProblem.badRequest(detail);
        }

        return uri;
    }
}
