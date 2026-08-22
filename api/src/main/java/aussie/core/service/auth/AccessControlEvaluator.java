package aussie.core.service.auth;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.model.auth.AccessControlConfig;
import aussie.core.model.auth.ServiceAccessConfig;
import aussie.core.model.common.SourceIdentifier;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.service.common.IpNetwork;

/**
 * Evaluates the network boundary for private endpoints.
 *
 * <p>The global IP policy is mandatory. A service policy is an additional intersection
 * and can only narrow the global result. Request Host and forwarding host metadata are
 * deliberately excluded: they describe the requested authority, not caller identity.
 */
@ApplicationScoped
public class AccessControlEvaluator {

    private static final Logger LOG = Logger.getLogger(AccessControlEvaluator.class);

    private final List<IpNetwork> globalAllowedNetworks;

    @Inject
    public AccessControlEvaluator(AccessControlConfig config) {
        this.globalAllowedNetworks = parseConfiguredNetworks(config.allowedIps().orElse(List.of()), "global");
        if (config.allowedDomains().filter(values -> !values.isEmpty()).isPresent()
                || config.allowedSubdomains()
                        .filter(values -> !values.isEmpty())
                        .isPresent()) {
            LOG.warn("Domain-based access-control settings are ignored; configure allowed-ips with trusted client IPs");
        }
    }

    public boolean isAllowed(
            SourceIdentifier source, RouteLookupResult route, Optional<ServiceAccessConfig> serviceConfig) {

        if (EndpointVisibility.PUBLIC.equals(route.visibility())) {
            return true;
        }

        if (!matches(source.ipAddress(), globalAllowedNetworks)) {
            return false;
        }

        if (serviceConfig.isEmpty() || !serviceConfig.get().hasRestrictions()) {
            return true;
        }

        // A domain-only service policy cannot establish caller identity and therefore
        // matches no source. Registration validation also rejects new domain policies;
        // this fail-closed behavior protects legacy stored registrations.
        final var servicePatterns = serviceConfig.get().allowedIps().orElse(List.of());
        return matches(source.ipAddress(), parseConfiguredNetworks(servicePatterns, "service"));
    }

    boolean matchesIp(String sourceIp, List<String> allowedPatterns) {
        return matches(sourceIp, parseConfiguredNetworks(allowedPatterns, "access-control"));
    }

    private boolean matches(String sourceIp, List<IpNetwork> networks) {
        final var source = IpNetwork.parse(sourceIp).filter(IpNetwork::isExactAddress);
        return source.isPresent() && networks.stream().anyMatch(network -> network.contains(source.get()));
    }

    private List<IpNetwork> parseConfiguredNetworks(List<String> patterns, String policyName) {
        return patterns.stream()
                .map(pattern -> IpNetwork.parse(pattern).orElseGet(() -> {
                    LOG.warnf("Ignoring invalid %s IP/CIDR: %s", policyName, pattern);
                    return null;
                }))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
