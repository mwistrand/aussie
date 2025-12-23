package aussie.spi;

import java.util.Optional;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;

/**
 * Service Provider Interface for JWS token issuance.
 *
 * <p>Implementations sign JWS tokens that Aussie forwards to backend services.
 * The default implementation uses RS256 (RSA with SHA-256).
 */
public interface TokenIssuerProvider {

    /**
     * Unique name identifying this issuer.
     *
     * @return the issuer name (e.g., "rs256", "es256")
     */
    String name();

    /**
     * Check if this issuer is available (has valid signing keys).
     *
     * @return true if the issuer can sign tokens
     */
    boolean isAvailable();

    /**
     * Issue a signed JWS token for backend services.
     *
     * <p>The token should include:
     * <ul>
     *   <li>Standard JWT claims (iss, sub, iat, exp)</li>
     *   <li>Claims forwarded from the original token</li>
     *   <li>The original issuer as "original_iss" claim</li>
     * </ul>
     *
     * @param validated the validated incoming token
     * @param config    JWS configuration
     * @return signed Aussie token
     */
    AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config);

    /**
     * Issue a signed JWS token for backend services with audience support.
     *
     * <p>The token should include:
     * <ul>
     *   <li>Standard JWT claims (iss, sub, iat, exp)</li>
     *   <li>Claims forwarded from the original token</li>
     *   <li>The original issuer as "original_iss" claim</li>
     *   <li>The audience claim (aud) if provided</li>
     * </ul>
     *
     * @param validated the validated incoming token
     * @param config    JWS configuration
     * @param audience  the audience claim to include (if present)
     * @return signed Aussie token
     */
    default AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config, Optional<String> audience) {
        // Default implementation ignores audience for backward compatibility
        // Implementations should override this method to include the audience claim
        return issue(validated, config);
    }
}
