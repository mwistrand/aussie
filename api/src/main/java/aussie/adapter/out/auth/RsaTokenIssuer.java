package aussie.adapter.out.auth;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.core.service.auth.SigningKeyRegistry;
import aussie.spi.TokenIssuerProvider;

/**
 * RS256 (RSA with SHA-256) token issuer.
 *
 * <p>
 * Signs JWS tokens using either:
 * <ul>
 * <li>Dynamic keys from {@link SigningKeyRegistry} when key rotation is
 * enabled</li>
 * <li>Static key from configuration when key rotation is disabled</li>
 * </ul>
 */
@ApplicationScoped
public class RsaTokenIssuer implements TokenIssuerProvider {

    private static final Logger LOG = Logger.getLogger(RsaTokenIssuer.class);

    private final SigningKeyRegistry keyRegistry;
    private final KeyRotationConfig keyRotationConfig;
    private final PrivateKey staticSigningKey;
    private final String staticKeyId;
    private final boolean staticKeyAvailable;

    @Inject
    public RsaTokenIssuer(RouteAuthConfig config, KeyRotationConfig keyRotationConfig, SigningKeyRegistry keyRegistry) {
        this.keyRegistry = keyRegistry;
        this.keyRotationConfig = keyRotationConfig;

        // Load static key for backward compatibility (when key rotation is disabled)
        PrivateKey key = null;
        boolean isAvailable = false;

        if (config.enabled() && config.jws().signingKey().isPresent()) {
            try {
                key = SigningKeyRecord.parsePrivateKey(config.jws().signingKey().get());
                isAvailable = true;
                LOG.info("RSA token issuer initialized with static signing key");
            } catch (IllegalArgumentException e) {
                LOG.errorv(e, "Failed to load RSA signing key");
            }
        }

        this.staticSigningKey = key;
        this.staticKeyId = config.jws().keyId();
        this.staticKeyAvailable = isAvailable;
    }

    @Override
    public String name() {
        return "rs256";
    }

    @Override
    public boolean isAvailable() {
        if (keyRotationConfig.enabled()) {
            return keyRegistry.isReady();
        }
        return staticKeyAvailable;
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config) {
        return issue(validated, config, Optional.empty());
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config, Optional<String> audience) {
        if (!isAvailable()) {
            throw new TokenIssuanceException("RSA signing key not configured");
        }

        try {
            final var signingContext = getSigningContext();
            final var claims = buildClaims(validated, config, audience);
            final var jws = signToken(claims, signingContext.privateKey(), signingContext.keyId());
            final var expiresAt = Instant.now().plus(config.tokenTtl());

            final var forwardedClaims = new HashMap<String, Object>();
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

    /**
     * Get the signing context (key and key ID) based on configuration.
     */
    private SigningContext getSigningContext() {
        if (keyRotationConfig.enabled()) {
            final var keyRecord = keyRegistry.getCurrentSigningKey();
            return new SigningContext(keyRecord.privateKey(), keyRecord.keyId());
        }
        return new SigningContext(staticSigningKey, staticKeyId);
    }

    private record SigningContext(PrivateKey privateKey, String keyId) {}

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

    private String signToken(JwtClaims claims, PrivateKey privateKey, String keyId) throws JoseException {
        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(privateKey);
        jws.setKeyIdHeaderValue(keyId);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    /**
     * Exception thrown when token issuance fails.
     */
    public static class TokenIssuanceException extends RuntimeException {
        public TokenIssuanceException(String message) {
            super(message);
        }

        public TokenIssuanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
