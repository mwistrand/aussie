package aussie.adapter.out.auth;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.spi.TokenIssuerProvider;

/**
 * RS256 (RSA with SHA-256) token issuer.
 *
 * <p>Signs JWS tokens using an RSA private key configured via application properties.
 */
@ApplicationScoped
public class RsaTokenIssuer implements TokenIssuerProvider {

    private static final Logger LOG = Logger.getLogger(RsaTokenIssuer.class);

    private final PrivateKey signingKey;
    private final boolean available;

    @Inject
    public RsaTokenIssuer(RouteAuthConfig config) {
        PrivateKey key = null;
        boolean isAvailable = false;

        if (config.enabled() && config.jws().signingKey().isPresent()) {
            try {
                key = loadPrivateKey(config.jws().signingKey().get());
                isAvailable = true;
                LOG.info("RSA token issuer initialized with signing key");
            } catch (Exception e) {
                LOG.errorv(e, "Failed to load RSA signing key");
            }
        }

        this.signingKey = key;
        this.available = isAvailable;
    }

    @Override
    public String name() {
        return "rs256";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config) {
        return issue(validated, config, Optional.empty());
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config, Optional<String> audience) {
        if (!available) {
            throw new TokenIssuanceException("RSA signing key not configured");
        }

        try {
            JwtClaims claims = buildClaims(validated, config, audience);
            String jws = signToken(claims, config);
            Instant expiresAt = Instant.now().plus(config.tokenTtl());

            Map<String, Object> forwardedClaims = new HashMap<>();
            for (String claimName : config.forwardedClaims()) {
                if (validated.claims().containsKey(claimName)) {
                    forwardedClaims.put(claimName, validated.claims().get(claimName));
                }
            }

            return new AussieToken(jws, validated.subject(), expiresAt, forwardedClaims);
        } catch (JoseException e) {
            throw new TokenIssuanceException("Failed to sign token: " + e.getMessage(), e);
        }
    }

    private JwtClaims buildClaims(TokenValidationResult.Valid validated, JwsConfig config, Optional<String> audience) {
        JwtClaims claims = new JwtClaims();

        // Standard claims
        claims.setIssuer(config.issuer());
        claims.setSubject(validated.subject());
        claims.setIssuedAtToNow();
        claims.setExpirationTimeMinutesInTheFuture((float) config.tokenTtl().toMinutes());
        claims.setGeneratedJwtId();

        // Set audience claim if provided
        audience.ifPresent(claims::setAudience);

        // Preserve original issuer
        claims.setClaim("original_iss", validated.issuer());

        // Forward configured claims from original token
        for (String claimName : config.forwardedClaims()) {
            Object value = validated.claims().get(claimName);
            if (value != null && !isStandardClaim(claimName)) {
                claims.setClaim(claimName, value);
            }
        }

        return claims;
    }

    private boolean isStandardClaim(String claimName) {
        return "iss".equals(claimName)
                || "sub".equals(claimName)
                || "aud".equals(claimName)
                || "iat".equals(claimName)
                || "exp".equals(claimName)
                || "jti".equals(claimName);
    }

    private String signToken(JwtClaims claims, JwsConfig config) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signingKey);
        jws.setKeyIdHeaderValue(config.keyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private PrivateKey loadPrivateKey(String keyData) throws Exception {
        // Support both raw base64 and PEM format
        String keyContent = keyData.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(keyContent);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(keySpec);
    }

    public static class TokenIssuanceException extends RuntimeException {
        public TokenIssuanceException(String message) {
            super(message);
        }

        public TokenIssuanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
