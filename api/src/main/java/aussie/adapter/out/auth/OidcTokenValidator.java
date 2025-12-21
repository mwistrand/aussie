package aussie.adapter.out.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.lang.JoseException;

import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.out.JwksCache;
import aussie.spi.TokenValidatorProvider;

/**
 * OIDC-compliant token validator using JWKS for signature verification.
 *
 * <p>
 * Validates JWT tokens according to OIDC Core 1.0 specification:
 * <ul>
 * <li>Verifies signature using JWKS from the provider</li>
 * <li>Validates issuer (iss) claim</li>
 * <li>Validates audience (aud) claim if configured</li>
 * <li>Validates expiration (exp) and not-before (nbf) claims</li>
 * </ul>
 */
@ApplicationScoped
public class OidcTokenValidator implements TokenValidatorProvider {

    private static final Logger LOG = Logger.getLogger(OidcTokenValidator.class);
    private static final int CLOCK_SKEW_SECONDS = 30;

    private final JwksCache jwksCache;

    @Inject
    public OidcTokenValidator(JwksCache jwksCache) {
        this.jwksCache = jwksCache;
    }

    @Override
    public String name() {
        return "oidc";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Uni<TokenValidationResult> validate(String token, TokenProviderConfig config) {
        if (token == null || token.isBlank()) {
            return Uni.createFrom().item(new TokenValidationResult.NoToken());
        }

        return extractKeyId(token)
                .flatMap(keyId -> jwksCache.getKey(config.jwksUri(), keyId))
                .flatMap(keyOpt -> {
                    if (keyOpt.isEmpty()) {
                        // Key not found, try refreshing JWKS (key rotation scenario)
                        return retryWithRefresh(token, config);
                    }
                    return validateWithKey(token, config, keyOpt.get());
                })
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv("Token validation failed: {0}", error.getMessage());
                    return new TokenValidationResult.Invalid(error.getMessage());
                });
    }

    private Uni<String> extractKeyId(String token) {
        return Uni.createFrom().item(() -> {
            try {
                JsonWebSignature jws = new JsonWebSignature();
                jws.setCompactSerialization(token);
                return jws.getKeyIdHeaderValue();
            } catch (JoseException e) {
                throw new TokenParseException("Failed to parse token: " + e.getMessage(), e);
            }
        });
    }

    private Uni<TokenValidationResult> retryWithRefresh(String token, TokenProviderConfig config) {
        LOG.infov("Key not found, refreshing JWKS for {0}", config.issuer());
        return extractKeyId(token)
                .flatMap(keyId -> jwksCache.refresh(config.jwksUri()).map(keySet -> keySet.getJsonWebKeys().stream()
                        .filter(k -> keyId == null || keyId.equals(k.getKeyId()))
                        .findFirst()))
                .flatMap(keyOpt -> {
                    if (keyOpt.isEmpty()) {
                        return Uni.createFrom()
                                .item(new TokenValidationResult.Invalid("Signing key not found in JWKS"));
                    }
                    return validateWithKey(token, config, keyOpt.get());
                });
    }

    private Uni<TokenValidationResult> validateWithKey(String token, TokenProviderConfig config, JsonWebKey key) {
        return Uni.createFrom().item(() -> {
            try {
                JwtConsumerBuilder builder = new JwtConsumerBuilder()
                        .setRequireSubject()
                        .setRequireExpirationTime()
                        .setAllowedClockSkewInSeconds(CLOCK_SKEW_SECONDS)
                        .setExpectedIssuer(config.issuer())
                        .setVerificationKey(key.getKey());

                // Add audience validation if configured
                if (!config.audiences().isEmpty()) {
                    builder.setExpectedAudience(config.audiences().toArray(new String[0]));
                } else {
                    builder.setSkipDefaultAudienceValidation();
                }

                JwtConsumer consumer = builder.build();
                JwtClaims claims = consumer.processToClaims(token);

                return buildValidResult(claims, config);
            } catch (InvalidJwtException e) {
                LOG.debugv("JWT validation failed: {0}", e.getMessage());
                return new TokenValidationResult.Invalid(summarizeJwtError(e));
            }
        });
    }

    private TokenValidationResult buildValidResult(JwtClaims claims, TokenProviderConfig config) {
        try {
            String subject = claims.getSubject();
            String issuer = claims.getIssuer();
            NumericDate expiration = claims.getExpirationTime();

            Map<String, Object> claimsMap = new HashMap<>(claims.getClaimsMap());

            // Apply claims mapping if configured
            for (var mapping : config.claimsMapping().entrySet()) {
                String externalName = mapping.getKey();
                String internalName = mapping.getValue();
                if (claimsMap.containsKey(externalName)) {
                    claimsMap.put(internalName, claimsMap.get(externalName));
                }
            }

            return new TokenValidationResult.Valid(
                    subject, issuer, claimsMap, Instant.ofEpochSecond(expiration.getValue()));
        } catch (MalformedClaimException e) {
            return new TokenValidationResult.Invalid("Malformed claims: " + e.getMessage());
        }
    }

    private String summarizeJwtError(InvalidJwtException e) {
        if (e.hasExpired()) {
            return "Token has expired";
        }
        if (e.getMessage().contains("issuer")) {
            return "Invalid token issuer";
        }
        if (e.getMessage().contains("audience")) {
            return "Invalid token audience";
        }
        if (e.getMessage().contains("signature")) {
            return "Invalid token signature";
        }
        return "Token validation failed";
    }

    public static class TokenParseException extends RuntimeException {
        public TokenParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
