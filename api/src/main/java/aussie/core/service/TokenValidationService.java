package aussie.core.service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.config.RouteAuthConfig;
import aussie.core.model.TokenProviderConfig;
import aussie.core.model.TokenValidationResult;
import aussie.spi.TokenValidatorProvider;

/**
 * Service that orchestrates token validation across configured providers.
 *
 * <p>Loads provider configurations from application properties and delegates
 * validation to the appropriate {@link TokenValidatorProvider} implementation.
 */
@ApplicationScoped
public class TokenValidationService {

    private static final Logger LOG = Logger.getLogger(TokenValidationService.class);

    private final List<TokenValidatorProvider> validators;
    private final Map<String, TokenProviderConfig> providerConfigs;
    private final boolean enabled;

    @Inject
    public TokenValidationService(Instance<TokenValidatorProvider> validatorInstances, RouteAuthConfig config) {
        this.validators = validatorInstances.stream()
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .filter(TokenValidatorProvider::isAvailable)
                .toList();
        this.enabled = config.enabled();
        this.providerConfigs = new ConcurrentHashMap<>();

        if (enabled) {
            loadProviderConfigs(config);
            LOG.infov(
                    "TokenValidationService initialized with {0} validators and {1} providers",
                    validators.size(), providerConfigs.size());
        }
    }

    private void loadProviderConfigs(RouteAuthConfig config) {
        for (var entry : config.providers().entrySet()) {
            String providerId = entry.getKey();
            var props = entry.getValue();

            try {
                var providerConfig = TokenProviderConfig.builder(
                                providerId, props.issuer(), URI.create(props.jwksUri()))
                        .discoveryUri(props.discoveryUri().map(URI::create).orElse(null))
                        .audiences(props.audiences())
                        .keyRefreshInterval(props.keyRefreshInterval())
                        .claimsMapping(props.claimsMapping())
                        .build();

                providerConfigs.put(providerId, providerConfig);
                LOG.infov("Loaded token provider config: {0} (issuer: {1})", providerId, props.issuer());
            } catch (Exception e) {
                LOG.errorv(e, "Failed to load token provider config: {0}", providerId);
            }
        }
    }

    /**
     * Check if route authentication is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Validate a bearer token against all configured providers.
     *
     * @param token the bearer token (without "Bearer " prefix)
     * @return validation result
     */
    public Uni<TokenValidationResult> validate(String token) {
        if (!enabled) {
            return Uni.createFrom().item(new TokenValidationResult.NoToken());
        }

        if (token == null || token.isBlank()) {
            return Uni.createFrom().item(new TokenValidationResult.NoToken());
        }

        if (providerConfigs.isEmpty()) {
            LOG.warn("No token providers configured");
            return Uni.createFrom().item(new TokenValidationResult.Invalid("No token providers configured"));
        }

        // Try each provider config with each validator
        return validateWithProviders(token, providerConfigs.values().stream().toList(), 0);
    }

    private Uni<TokenValidationResult> validateWithProviders(
            String token, List<TokenProviderConfig> configs, int index) {
        if (index >= configs.size()) {
            return Uni.createFrom().item(new TokenValidationResult.Invalid("Token not accepted by any provider"));
        }

        var config = configs.get(index);
        return validateWithValidators(token, config, 0).flatMap(result -> {
            if (result instanceof TokenValidationResult.Valid) {
                return Uni.createFrom().item(result);
            }
            // Try next provider
            return validateWithProviders(token, configs, index + 1);
        });
    }

    private Uni<TokenValidationResult> validateWithValidators(String token, TokenProviderConfig config, int index) {
        if (index >= validators.size()) {
            return Uni.createFrom().item(new TokenValidationResult.Invalid("No validator accepted the token"));
        }

        var validator = validators.get(index);
        return validator.validate(token, config).flatMap(result -> {
            if (result instanceof TokenValidationResult.Valid) {
                LOG.debugv("Token validated by {0} for issuer {1}", validator.name(), config.issuer());
                return Uni.createFrom().item(result);
            }
            // Try next validator
            return validateWithValidators(token, config, index + 1);
        });
    }

    /**
     * Get a specific provider configuration by ID.
     */
    public TokenProviderConfig getProviderConfig(String providerId) {
        return providerConfigs.get(providerId);
    }
}
