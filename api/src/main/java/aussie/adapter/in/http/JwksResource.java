package aussie.adapter.in.http;

import java.math.BigInteger;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.service.auth.SigningKeyRegistry;

/**
 * JWKS (JSON Web Key Set) endpoint for exposing Aussie's public signing keys.
 *
 * <p>This endpoint returns all keys valid for verification (ACTIVE and DEPRECATED),
 * allowing downstream services to validate tokens signed by Aussie.
 *
 * <p>The response follows the JWKS format defined in RFC 7517.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7517">RFC 7517 - JSON Web Key (JWK)</a>
 */
@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class JwksResource {

    private static final Logger LOG = Logger.getLogger(JwksResource.class);
    private static final int CACHE_MAX_AGE_SECONDS = 3600;

    private final SigningKeyRegistry keyRegistry;
    private final KeyRotationConfig keyRotationConfig;

    @Inject
    public JwksResource(SigningKeyRegistry keyRegistry, KeyRotationConfig keyRotationConfig) {
        this.keyRegistry = keyRegistry;
        this.keyRotationConfig = keyRotationConfig;
    }

    /**
     * Get the JWKS containing all public keys valid for verification.
     *
     * <p>Response format:
     * <pre>{@code
     * {
     *   "keys": [
     *     {
     *       "kty": "RSA",
     *       "kid": "k-2024-q1-abc123",
     *       "use": "sig",
     *       "alg": "RS256",
     *       "n": "...",
     *       "e": "..."
     *     }
     *   ]
     * }
     * }</pre>
     *
     * @return JWKS response with all verification keys
     */
    @GET
    @Path("/.well-known/jwks.json")
    public Response getJwks() {
        if (!keyRotationConfig.enabled()) {
            LOG.debug("Key rotation disabled, JWKS endpoint returning empty set");
            return Response.ok(Map.of("keys", List.of())).build();
        }

        final var verificationKeys = keyRegistry.getVerificationKeys();

        if (verificationKeys.isEmpty()) {
            LOG.warn("No verification keys available");
            return Response.ok(Map.of("keys", List.of())).build();
        }

        final var jwks = verificationKeys.stream().map(this::toJwk).toList();

        LOG.debugv("Returning JWKS with {0} keys", jwks.size());

        return Response.ok(Map.of("keys", jwks))
                .header("Cache-Control", "public, max-age=" + CACHE_MAX_AGE_SECONDS)
                .build();
    }

    /**
     * Convert a SigningKeyRecord to JWK format.
     */
    private Map<String, Object> toJwk(SigningKeyRecord keyRecord) {
        final var publicKey = keyRecord.publicKey();

        return Map.of(
                "kty",
                "RSA",
                "kid",
                keyRecord.keyId(),
                "use",
                "sig",
                "alg",
                "RS256",
                "n",
                base64UrlEncode(publicKey.getModulus()),
                "e",
                base64UrlEncode(publicKey.getPublicExponent()));
    }

    /**
     * Base64 URL encode a BigInteger for JWK format.
     *
     * <p>Per RFC 7518, the integers are encoded as unsigned big-endian bytes
     * without leading zeros, then base64url encoded.
     */
    private String base64UrlEncode(BigInteger value) {
        var bytes = value.toByteArray();

        // Remove leading zero byte if present (BigInteger uses two's complement)
        if (bytes.length > 1 && bytes[0] == 0) {
            final var trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
