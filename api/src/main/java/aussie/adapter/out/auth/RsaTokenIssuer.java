package aussie.adapter.out.auth;

import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.lang.JoseException;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.core.service.auth.IssuedClaimPolicy;
import aussie.core.service.auth.SigningKeyRegistry;
import aussie.spi.TokenIssuerProvider;

/**
 * RS256 (RSA with SHA-256) token issuer.
 *
 * <p>Signs every token with the active key from {@link SigningKeyRegistry}.
 */
@ApplicationScoped
public class RsaTokenIssuer implements TokenIssuerProvider {

    private static final String TOKEN_PROFILE = "aussie+jwt-v1";

    private final SigningKeyRegistry keyRegistry;

    @Inject
    public RsaTokenIssuer(SigningKeyRegistry keyRegistry) {
        this.keyRegistry = keyRegistry;
    }

    @Override
    public String name() {
        return "rs256";
    }

    @Override
    public boolean isAvailable() {
        return keyRegistry.isReady();
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config) {
        return issue(validated, config, Optional.empty());
    }

    @Override
    public AussieToken issue(TokenValidationResult.Valid validated, JwsConfig config, Optional<String> audience) {
        final SigningKeyRecord signingContext;
        try {
            signingContext = keyRegistry.getCurrentSigningKey();
        } catch (IllegalStateException e) {
            throw new TokenIssuanceException("RSA signing key not configured", e);
        }
        final var effectiveAudience = audience.filter(value -> !value.isBlank())
                .or(config::defaultAudience)
                .filter(value -> !value.isBlank());
        if (effectiveAudience.isEmpty()) {
            throw new TokenIssuanceException("Issued tokens require an audience");
        }

        try {
            final var issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            if (validated.expiresAt() == null) {
                throw new TokenIssuanceException("Validated identity has no expiration");
            }
            final var expiresAt = issuedAt.plus(config.effectiveTtl(Duration.between(issuedAt, validated.expiresAt())))
                    .truncatedTo(ChronoUnit.SECONDS);
            if (!expiresAt.isAfter(issuedAt)) {
                throw new TokenIssuanceException("Validated identity has expired");
            }

            final var claims = buildClaims(validated, config, effectiveAudience, issuedAt, expiresAt);
            final var jws = signToken(claims, signingContext.privateKey(), signingContext.keyId());

            final var forwardedClaims = new HashMap<String, Object>();
            for (String claimName : config.forwardedClaims()) {
                final var value = validated.claims().get(claimName);
                if (value != null && !isStandardClaim(claimName)) {
                    forwardedClaims.put(claimName, value);
                }
            }

            return new AussieToken(jws, validated.subject(), expiresAt, forwardedClaims);
        } catch (JoseException e) {
            throw new TokenIssuanceException("Failed to sign token: " + e.getMessage(), e);
        }
    }

    private JwtClaims buildClaims(
            TokenValidationResult.Valid validated,
            JwsConfig config,
            Optional<String> audience,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaims claims = new JwtClaims();

        // Standard claims
        claims.setIssuer(config.issuer());
        claims.setSubject(validated.subject());
        claims.setIssuedAt(NumericDate.fromSeconds(issuedAt.getEpochSecond()));
        claims.setNotBefore(NumericDate.fromSeconds(issuedAt.getEpochSecond()));
        claims.setExpirationTime(NumericDate.fromSeconds(expiresAt.getEpochSecond()));
        claims.setGeneratedJwtId();

        // Set audience claim if provided
        audience.ifPresent(claims::setAudience);

        // Preserve original issuer
        claims.setClaim("original_iss", validated.issuer());
        claims.setClaim("original_provider", validated.identity().providerId());
        claims.setClaim("aussie_token_profile", TOKEN_PROFILE);

        // Forward configured claims from original token
        for (String claimName : config.forwardedClaims()) {
            Object value = validated.claims().get(claimName);
            if (value != null && !isStandardClaim(claimName)) {
                claims.setClaim(claimName, value);
            }
        }
        if (claims.getClaimsMap().entrySet().stream()
                .anyMatch(entry -> !IssuedClaimPolicy.isAllowed(entry.getKey(), entry.getValue()))) {
            throw new TokenIssuanceException("Claim violates the issued-token policy");
        }

        return claims;
    }

    private boolean isStandardClaim(String claimName) {
        return "iss".equals(claimName)
                || "sub".equals(claimName)
                || "aud".equals(claimName)
                || "iat".equals(claimName)
                || "nbf".equals(claimName)
                || "exp".equals(claimName)
                || "jti".equals(claimName)
                || "original_iss".equals(claimName)
                || "original_provider".equals(claimName)
                || "aussie_token_profile".equals(claimName);
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
