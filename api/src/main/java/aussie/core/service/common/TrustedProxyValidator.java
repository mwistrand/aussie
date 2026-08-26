package aussie.core.service.common;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.config.TrustedProxyConfig;

/**
 * Validates whether a socket IP belongs to a trusted proxy.
 *
 * <p>Configured networks are parsed once. Request-supplied addresses are never cached.
 */
@ApplicationScoped
public class TrustedProxyValidator {

    private static final Logger LOG = Logger.getLogger(TrustedProxyValidator.class);

    private final TrustedProxyConfig config;
    private final List<IpNetwork> trustedNetworks;

    @Inject
    public TrustedProxyValidator(TrustedProxyConfig config) {
        this.config = config;
        this.trustedNetworks = parseConfiguredNetworks(config.proxies().orElse(List.of()));
    }

    /**
     * Check if forwarding headers should be trusted for the given socket IP.
     *
     * @param socketIp the direct connection's remote IP address
     * @return true if forwarding headers should be trusted
     */
    public boolean shouldTrustForwardingHeaders(String socketIp) {
        return isTrustedProxy(socketIp);
    }

    /**
     * Check whether an address is one of the configured trusted proxy hops.
     * This is also used while walking a forwarding chain from right to left.
     */
    public boolean isTrustedProxy(String socketIp) {
        if (!config.enabled()) {
            return false;
        }
        if (socketIp == null || socketIp.isEmpty()) {
            return false;
        }
        if (trustedNetworks.isEmpty()) {
            return false;
        }
        final var source = IpNetwork.parse(socketIp).filter(IpNetwork::isExactAddress);
        return source.isPresent() && trustedNetworks.stream().anyMatch(network -> network.contains(source.get()));
    }

    private List<IpNetwork> parseConfiguredNetworks(List<String> patterns) {
        return patterns.stream()
                .map(pattern -> IpNetwork.parse(pattern).orElseGet(() -> {
                    LOG.warnf("Ignoring invalid trusted-proxy IP/CIDR: %s", pattern);
                    return null;
                }))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
