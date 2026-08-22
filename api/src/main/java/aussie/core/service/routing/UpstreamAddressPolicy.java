package aussie.core.service.routing;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Applies the gateway's SSRF address policy to upstream hosts.
 */
public final class UpstreamAddressPolicy {

    private UpstreamAddressPolicy() {}

    /**
     * Check whether an upstream host resolves to an address the gateway must not route to.
     *
     * <p>Loopback, link-local, and wildcard addresses are always blocked. Site-local
     * addresses are blocked unless private upstreams are explicitly enabled. Hosts that
     * cannot be resolved are left to the operator-owned host allowlist and runtime DNS.
     *
     * @param host the upstream host name or IP address
     * @param allowPrivateUpstreams whether site-local addresses are allowed
     * @return true when the host is blocked by the address policy
     */
    public static boolean isBlocked(String host, boolean allowPrivateUpstreams) {
        if (host.equalsIgnoreCase("localhost") || host.equals("0.0.0.0") || host.equals("::")) {
            return true;
        }

        try {
            final var address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                return true;
            }
            return !allowPrivateUpstreams && address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
