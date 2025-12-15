package aussie.core.service.auth;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.spi.TokenIssuerProvider;

/**
 * Service that coordinates JWS token issuance.
 *
 * <p>Uses configured {@link TokenIssuerProvider} to sign tokens that Aussie
 * forwards to backend services.
 */
@ApplicationScoped
public class TokenIssuanceService {

    private static final Logger LOG = Logger.getLogger(TokenIssuanceService.class);

    private final List<TokenIssuerProvider> issuers;
    private final JwsConfig jwsConfig;
    private final boolean enabled;

    @Inject
    public TokenIssuanceService(Instance<TokenIssuerProvider> issuerInstances, RouteAuthConfig config) {
        this.issuers = issuerInstances.stream()
                .filter(TokenIssuerProvider::isAvailable)
                .toList();

        this.enabled = config.enabled();
        if (enabled) {
            this.jwsConfig = new JwsConfig(
                    config.jws().issuer(),
                    config.jws().keyId(),
                    config.jws().tokenTtl(),
                    config.jws().forwardedClaims());
            LOG.infov("TokenIssuanceService initialized with {0} issuers", issuers.size());
        } else {
            this.jwsConfig = null;
        }
    }

    /**
     * Check if token issuance is enabled and configured.
     */
    public boolean isEnabled() {
        return enabled && !issuers.isEmpty();
    }

    /**
     * Issue a signed Aussie token for the validated request.
     *
     * @param validated the validated incoming token
     * @return the signed Aussie token, or empty if issuance is not configured
     */
    public Optional<AussieToken> issue(TokenValidationResult.Valid validated) {
        if (!isEnabled()) {
            LOG.debug("Token issuance not enabled or no issuers available");
            return Optional.empty();
        }

        try {
            // Use the first available issuer
            TokenIssuerProvider issuer = issuers.get(0);
            AussieToken token = issuer.issue(validated, jwsConfig);
            LOG.debugv("Issued token for subject {0} using {1}", validated.subject(), issuer.name());
            return Optional.of(token);
        } catch (Exception e) {
            LOG.errorv(e, "Failed to issue token for subject {0}", validated.subject());
            return Optional.empty();
        }
    }

    /**
     * Get the current JWS configuration.
     */
    public JwsConfig getJwsConfig() {
        return jwsConfig;
    }
}
