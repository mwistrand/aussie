package aussie.adapter.out.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;
import org.jose4j.base64url.Base64Url;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.lang.JoseException;

import aussie.core.config.ResiliencyConfig;
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
    private static final int MAX_CONSUMER_CACHE_SIZE = 64;
    private static final String KID_HEADER = "kid";

    private final JwksCache jwksCache;
    private final Cache<ConsumerKey, JwtConsumer> consumerCache;

    @Inject
    public OidcTokenValidator(JwksCache jwksCache, ResiliencyConfig resiliencyConfig) {
        this.jwksCache = jwksCache;
        this.consumerCache = Caffeine.newBuilder()
                .maximumSize(MAX_CONSUMER_CACHE_SIZE)
                .expireAfterWrite(resiliencyConfig.jwks().cacheTtl())
                .build();
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

        return parseHeader(token)
                .flatMap(parsed -> jwksCache
                        .getKey(config.jwksUri(), parsed.kid())
                        .flatMap(keyOpt -> {
                            if (keyOpt.isEmpty()) {
                                // Key not found, try refreshing JWKS (key rotation scenario)
                                return retryWithRefresh(parsed, config);
                            }
                            return validateWithKey(parsed, config, keyOpt.get());
                        }))
                .onFailure()
                .recoverWithItem(error -> {
                    LOG.warnv("Token validation failed: {0}", error.getMessage());
                    return new TokenValidationResult.Invalid(error.getMessage());
                });
    }

    private Uni<ParsedToken> parseHeader(String token) {
        return Uni.createFrom().item(() -> {
            // Strict structural validation (segment count, alg sanity) is delegated to the
            // cached JwtConsumer further down the pipeline; here we only need the kid for
            // JWKS lookup, so a header-only parse is enough.
            final var firstDot = token.indexOf('.');
            if (firstDot <= 0) {
                throw new TokenParseException("Token is not a JWT", null);
            }
            try {
                final var headerJson = Base64Url.decodeToUtf8String(token.substring(0, firstDot));
                final Map<String, Object> headers = JsonUtil.parseJson(headerJson);
                final var kidValue = headers.get(KID_HEADER);
                final var kid = kidValue == null ? null : kidValue.toString();
                return new ParsedToken(token, kid);
            } catch (JoseException | IllegalArgumentException e) {
                throw new TokenParseException("Failed to parse token: " + e.getMessage(), e);
            }
        });
    }

    private Uni<TokenValidationResult> retryWithRefresh(ParsedToken parsed, TokenProviderConfig config) {
        LOG.infov("Key not found, refreshing JWKS for {0}", config.issuer());
        final var kid = parsed.kid();
        return jwksCache
                .refresh(config.jwksUri())
                .map(keySet -> keySet.getJsonWebKeys().stream()
                        .filter(k -> kid == null || kid.equals(k.getKeyId()))
                        .findFirst())
                .flatMap(keyOpt -> {
                    if (keyOpt.isEmpty()) {
                        return Uni.createFrom()
                                .item(new TokenValidationResult.Invalid("Signing key not found in JWKS"));
                    }
                    return validateWithKey(parsed, config, keyOpt.get());
                });
    }

    private Uni<TokenValidationResult> validateWithKey(ParsedToken parsed, TokenProviderConfig config, JsonWebKey key) {
        return Uni.createFrom().item(() -> {
            try {
                final var consumer = getOrBuildConsumer(config, key);
                final var claims = consumer.processToClaims(parsed.token());
                return buildValidResult(claims, config);
            } catch (InvalidJwtException e) {
                LOG.debugv("JWT validation failed: {0}", e.getMessage());
                return new TokenValidationResult.Invalid(summarizeJwtError(e));
            }
        });
    }

    private JwtConsumer getOrBuildConsumer(TokenProviderConfig config, JsonWebKey key) {
        // Snapshot audiences to guarantee a stable equals/hashCode for the cache key,
        // independent of the Set implementation supplied by config.
        final var cacheKey = new ConsumerKey(config.issuer(), Set.copyOf(config.audiences()), key.getKeyId());
        return consumerCache.get(cacheKey, k -> buildConsumer(config, key));
    }

    private JwtConsumer buildConsumer(TokenProviderConfig config, JsonWebKey key) {
        final var builder = new JwtConsumerBuilder()
                .setRequireSubject()
                .setRequireExpirationTime()
                .setAllowedClockSkewInSeconds(CLOCK_SKEW_SECONDS)
                .setExpectedIssuer(config.issuer())
                .setVerificationKey(key.getKey());

        if (!config.audiences().isEmpty()) {
            builder.setExpectedAudience(config.audiences().toArray(new String[0]));
        } else {
            builder.setSkipDefaultAudienceValidation();
        }

        return builder.build();
    }

    private TokenValidationResult buildValidResult(JwtClaims claims, TokenProviderConfig config) {
        try {
            final var subject = claims.getSubject();
            final var issuer = claims.getIssuer();
            final var expiration = claims.getExpirationTime();

            final var claimsMap = applyClaimsMapping(claims, config.claimsMapping());

            return new TokenValidationResult.Valid(
                    subject, issuer, claimsMap, Instant.ofEpochSecond(expiration.getValue()));
        } catch (MalformedClaimException e) {
            return new TokenValidationResult.Invalid("Malformed claims: " + e.getMessage());
        }
    }

    private Map<String, Object> applyClaimsMapping(JwtClaims claims, Map<String, String> mapping) {
        // Common case: no claims mapping configured. jose4j's JwtClaims.getClaimsMap()
        // returns a fresh LinkedHashMap on every call, so we can safely hand it to the
        // caller without the previous defensive `new HashMap<>(...)` copy.
        if (mapping.isEmpty()) {
            return claims.getClaimsMap();
        }
        final var claimsMap = new HashMap<>(claims.getClaimsMap());
        for (final var entry : mapping.entrySet()) {
            final var externalName = entry.getKey();
            final var internalName = entry.getValue();
            if (claimsMap.containsKey(externalName)) {
                claimsMap.put(internalName, claimsMap.get(externalName));
            }
        }
        return claimsMap;
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

    /**
     * Cache key for {@link JwtConsumer} instances.
     *
     * <p>A consumer is functionally identical for the same (issuer, audiences, kid) triple,
     * so we share one across requests instead of rebuilding the validator chain per call.
     */
    private record ConsumerKey(String issuer, Set<String> audiences, String kid) {}

    /**
     * A token plus the {@code kid} from its header, parsed once and reused so the JWS header
     * is not re-decoded between {@link JwksCache#getKey} and the {@link JwtConsumer} validate.
     */
    private record ParsedToken(String token, String kid) {}

    public static class TokenParseException extends RuntimeException {
        public TokenParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
