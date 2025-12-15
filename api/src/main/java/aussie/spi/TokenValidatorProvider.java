package aussie.spi;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;

/**
 * Service Provider Interface for token validation implementations.
 *
 * <p>Platform teams can implement this interface to support different token formats
 * and validation strategies. The built-in OIDC provider validates JWT tokens against
 * configured JWKS endpoints.
 *
 * <h2>Built-in Providers</h2>
 * <ul>
 *   <li><b>oidc</b>: Validates JWT tokens using OIDC discovery and JWKS (priority: 100)</li>
 * </ul>
 *
 * <h2>Provider Selection</h2>
 * <p>Providers are sorted by priority (highest first). The first provider that
 * returns a non-Skip result handles the request. If all providers skip, the
 * token is considered invalid.
 *
 * <h2>Example Custom Provider</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class CustomTokenValidator implements TokenValidatorProvider {
 *     @Override
 *     public String name() { return "custom"; }
 *
 *     @Override
 *     public int priority() { return 150; }
 *
 *     @Override
 *     public Uni<TokenValidationResult> validate(String token, TokenProviderConfig config) {
 *         // Custom validation logic...
 *     }
 * }
 * }</pre>
 */
public interface TokenValidatorProvider {

    /**
     * Unique name identifying this validator.
     *
     * @return the validator name (e.g., "oidc", "custom")
     */
    String name();

    /**
     * Priority for validator selection (higher = tried first).
     *
     * <p>Built-in validators use:
     * <ul>
     *   <li>oidc: 100</li>
     * </ul>
     *
     * @return the validator priority
     */
    default int priority() {
        return 0;
    }

    /**
     * Check if this validator is available and properly configured.
     *
     * @return true if the validator can process tokens
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Validate an incoming bearer token.
     *
     * <p>Implementations should:
     * <ul>
     *   <li>Parse the token (typically a JWT)</li>
     *   <li>Verify the signature against the provider's JWKS</li>
     *   <li>Validate standard claims (iss, aud, exp, nbf)</li>
     *   <li>Return {@link TokenValidationResult.Valid} with extracted claims</li>
     * </ul>
     *
     * @param token  the raw token string (without "Bearer " prefix)
     * @param config configuration for the token provider
     * @return validation result (Valid, Invalid, or NoToken)
     */
    Uni<TokenValidationResult> validate(String token, TokenProviderConfig config);
}
