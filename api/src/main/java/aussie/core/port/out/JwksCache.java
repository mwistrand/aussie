package aussie.core.port.out;

import java.net.URI;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;

/**
 * Port for caching and retrieving JSON Web Key Sets (JWKS).
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Fetching JWKS from remote endpoints</li>
 *   <li>Caching keys with appropriate TTL</li>
 *   <li>Handling key rotation gracefully</li>
 * </ul>
 */
public interface JwksCache {

    /**
     * Get the JSON Web Key Set for a provider.
     *
     * <p>Returns cached keys if available and not expired, otherwise fetches
     * fresh keys from the JWKS endpoint.
     *
     * @param jwksUri the JWKS endpoint URI
     * @return the JSON Web Key Set
     */
    Uni<JsonWebKeySet> getKeySet(URI jwksUri);

    /**
     * Get a specific key by ID.
     *
     * @param jwksUri the JWKS endpoint URI
     * @param keyId   the key ID (kid) to retrieve
     * @return the key if found
     */
    Uni<Optional<JsonWebKey>> getKey(URI jwksUri, String keyId);

    /**
     * Force refresh keys from the remote endpoint.
     *
     * <p>Use this when a key lookup fails and you suspect key rotation.
     *
     * @param jwksUri the JWKS endpoint URI
     * @return the refreshed key set
     */
    Uni<JsonWebKeySet> refresh(URI jwksUri);

    /**
     * Invalidate cached keys for a provider.
     *
     * @param jwksUri the JWKS endpoint URI
     */
    void invalidate(URI jwksUri);
}
