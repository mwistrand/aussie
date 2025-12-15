package aussie.spi;

import jakarta.ws.rs.core.MultivaluedMap;

import aussie.core.model.auth.AuthenticationResult;

/**
 * Service Provider Interface for authentication implementations.
 *
 * <p>Platform teams implement this interface to provide custom authentication
 * mechanisms (SAML, OAuth2, custom token validation, etc.). Built-in providers
 * include API key validation, JWT/OIDC, and a dangerous-noop mode for development.
 *
 * <p>Implementations are discovered via CDI and sorted by priority. The first
 * provider that returns a non-Skip result handles the request.
 *
 * <h2>Built-in Providers</h2>
 * <ul>
 *   <li><b>noop</b>: Development mode that allows all requests (dangerous-noop)</li>
 *   <li><b>api-key</b>: Validates API keys stored in the gateway</li>
 *   <li><b>jwt</b>: Validates JWT tokens against configured JWKS endpoints</li>
 * </ul>
 *
 * <h2>How to Create a Custom Provider</h2>
 * <ol>
 *   <li>Implement this interface as a CDI bean (@ApplicationScoped)</li>
 *   <li>Return a unique name from {@link #name()}</li>
 *   <li>Implement {@link #authenticate} to validate credentials</li>
 *   <li>Return appropriate {@link AuthenticationResult}</li>
 * </ol>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @ApplicationScoped
 * public class SamlAuthProvider implements AuthenticationProvider {
 *     @Override
 *     public String name() { return "saml"; }
 *
 *     @Override
 *     public int priority() { return 150; }
 *
 *     @Override
 *     public AuthenticationResult authenticate(MultivaluedMap<String, String> headers, String path) {
 *         String samlAssertion = headers.getFirst("X-SAML-Assertion");
 *         if (samlAssertion == null) {
 *             return AuthenticationResult.Skip.instance();
 *         }
 *         // Validate SAML assertion...
 *         return new AuthenticationResult.Success(context);
 *     }
 * }
 * }</pre>
 */
public interface AuthenticationProvider {

    /**
     * Unique name identifying this provider.
     *
     * <p>Used for logging, diagnostics, and configuration.
     *
     * @return the provider name (e.g., "api-key", "jwt", "saml")
     */
    String name();

    /**
     * Priority for provider selection (higher = tried first).
     *
     * <p>Built-in providers use:
     * <ul>
     *   <li>noop: Integer.MIN_VALUE (lowest priority, fallback only)</li>
     *   <li>api-key: 100</li>
     *   <li>jwt: 200</li>
     * </ul>
     *
     * <p>Custom providers should use priority &gt; 200 to be tried first.
     *
     * @return the provider priority
     */
    default int priority() {
        return 0;
    }

    /**
     * Check if this provider is available and properly configured.
     *
     * <p>Providers that return false are skipped entirely (not even considered
     * for authentication). Use this to disable providers based on configuration.
     *
     * @return true if the provider can handle authentication requests
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Attempt to authenticate a request.
     *
     * <p>Providers should return:
     * <ul>
     *   <li>{@link AuthenticationResult.Success} if authentication succeeded</li>
     *   <li>{@link AuthenticationResult.Failure} if credentials were provided but invalid</li>
     *   <li>{@link AuthenticationResult.Skip} if this provider doesn't handle this request type</li>
     * </ul>
     *
     * <p>Important: Only return Failure if the request contained credentials that
     * this provider recognizes but couldn't validate. Return Skip if no relevant
     * credentials were present (e.g., no Authorization header for Bearer token auth).
     *
     * @param headers HTTP headers from the request
     * @param path    request path (for path-based auth decisions)
     * @return authentication result
     */
    AuthenticationResult authenticate(MultivaluedMap<String, String> headers, String path);
}
