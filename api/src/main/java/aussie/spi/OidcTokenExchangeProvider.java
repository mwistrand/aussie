package aussie.spi;

import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;

import aussie.core.model.auth.OidcTokenExchangeRequest;
import aussie.core.model.auth.OidcTokenExchangeResponse;

/**
 * SPI for OIDC token exchange implementations.
 *
 * <p>Platform teams can implement this interface to customize how authorization
 * codes are exchanged for tokens with their identity provider. The built-in
 * implementation supports standard OAuth 2.0 token exchange (RFC 6749) with
 * client_secret_basic and client_secret_post authentication.
 *
 * <p>Built-in providers:
 * <ul>
 *   <li>default (priority: 100) - Standard OAuth 2.0 token exchange</li>
 * </ul>
 *
 * <p>Provider selection order:
 * <ol>
 *   <li>Configured provider (aussie.auth.oidc.token-exchange.provider)</li>
 *   <li>Highest priority available provider</li>
 * </ol>
 *
 * <h2>Custom Implementation Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class FederatedTokenExchangeProvider implements OidcTokenExchangeProvider {
 *
 *     @Override
 *     public String name() {
 *         return "federated";
 *     }
 *
 *     @Override
 *     public int priority() {
 *         return 150; // Higher than default
 *     }
 *
 *     @Override
 *     public Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request) {
 *         // Custom token exchange logic:
 *         // - Exchange with primary IdP
 *         // - Perform token exchange with federation target
 *         // - Return combined response
 *     }
 * }
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <pre>
 * # Enable token exchange
 * aussie.auth.oidc.token-exchange.enabled=true
 *
 * # Select provider by name
 * aussie.auth.oidc.token-exchange.provider=federated
 * </pre>
 *
 * @see OidcTokenExchangeRequest
 * @see OidcTokenExchangeResponse
 */
public interface OidcTokenExchangeProvider {

    /**
     * Return the provider name for configuration selection.
     *
     * <p>This name is used in the configuration property
     * {@code aussie.auth.oidc.token-exchange.provider} to select this provider.
     *
     * @return Provider name (e.g., "default", "federated")
     */
    String name();

    /**
     * Return the provider priority for automatic selection.
     *
     * <p>Higher priority providers are preferred when multiple providers
     * are available. Built-in priorities:
     * <ul>
     *   <li>default: 100</li>
     * </ul>
     *
     * <p>Custom implementations should use priorities above 100 to be
     * preferred over the default provider.
     *
     * @return Priority value (higher = more preferred)
     */
    default int priority() {
        return 0;
    }

    /**
     * Check if this provider is available and ready to use.
     *
     * <p>For the default provider, this checks if the token endpoint
     * is configured. Custom providers may check external dependencies.
     *
     * <p>This method may be called multiple times and should return
     * quickly. Consider caching the availability state.
     *
     * @return true if the provider can be used
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Create a health indicator for this provider.
     *
     * <p>The health indicator is used for the /q/health endpoint
     * to report the status of token exchange capability.
     *
     * @return Health check response, or empty if not supported
     */
    default Optional<HealthCheckResponse> healthCheck() {
        return Optional.empty();
    }

    /**
     * Exchange an authorization code for tokens.
     *
     * <p>Implementations should:
     * <ul>
     *   <li>POST to the IdP token endpoint with required parameters</li>
     *   <li>Authenticate using the configured client credentials</li>
     *   <li>Include PKCE code_verifier if provided in the request</li>
     *   <li>Parse and return the token response</li>
     * </ul>
     *
     * <p>The returned response must include at minimum an access_token.
     * ID tokens and refresh tokens are optional depending on granted scopes.
     *
     * @param request The token exchange request parameters
     * @return Token exchange response with tokens and metadata
     */
    Uni<OidcTokenExchangeResponse> exchange(OidcTokenExchangeRequest request);
}
