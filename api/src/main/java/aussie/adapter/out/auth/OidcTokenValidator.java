package aussie.adapter.out.auth;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;
import org.jose4j.base64url.Base64Url;
import org.jose4j.json.JsonUtil;
import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jwa.AlgorithmConstraints.ConstraintType;
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
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.port.out.JwksCache;
import aussie.core.util.SafeLogging;
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
    // Per-config derived state (canonical audiences set, pre-built expectedAudience array,
    // claims mapping snapshot). Each (issuer, audiences, mapping) tuple pays for the
    // Set.copyOf / toArray work exactly once instead of on every authenticated request.
    // Shares the JWKS cache TTL so config hot-reloads cannot pin stale claimsMapping past
    // the rotation window.
    private final Cache<TokenProviderConfig, IssuerState> issuerStateCache;

    @Inject
    public OidcTokenValidator(JwksCache jwksCache, ResiliencyConfig resiliencyConfig) {
        this.jwksCache = jwksCache;
        this.consumerCache = Caffeine.newBuilder()
                .maximumSize(MAX_CONSUMER_CACHE_SIZE)
                .expireAfterWrite(resiliencyConfig.jwks().cacheTtl())
                .build();
        this.issuerStateCache = Caffeine.newBuilder()
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
                    LOG.warnv("Token validation failed: error_type={0}", SafeLogging.errorType(error));
                    return new TokenValidationResult.Invalid("Token validation failed");
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
        LOG.infov("Key not found, refreshing JWKS for provider {0}", config.id());
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
                final var state = issuerState(config);
                final var consumer = getOrBuildConsumer(state, key);
                final var claims = consumer.processToClaims(parsed.token());
                return buildValidResult(claims, state);
            } catch (InvalidJwtException e) {
                LOG.debugv("JWT validation failed: error_type={0}", SafeLogging.errorType(e));
                return new TokenValidationResult.Invalid(summarizeJwtError(e));
            }
        });
    }

    private IssuerState issuerState(TokenProviderConfig config) {
        return issuerStateCache.get(config, IssuerState::of);
    }

    private JwtConsumer getOrBuildConsumer(IssuerState state, JsonWebKey key) {
        // Audiences are already canonicalized on the IssuerState, so reusing the same
        // immutable Set here keeps the cache key allocation-free for the hot path.
        final var cacheKey = new ConsumerKey(
                state.issuer(),
                state.audiences(),
                state.allowedAlgorithms(),
                key.getAlgorithm(),
                key.calculateBase64urlEncodedThumbprint("SHA-256"));
        return consumerCache.get(cacheKey, k -> buildConsumer(state, key));
    }

    private JwtConsumer buildConsumer(IssuerState state, JsonWebKey key) {
        final var keyAlgorithm = key.getAlgorithm();
        if (keyAlgorithm != null && !state.allowedAlgorithms().contains(keyAlgorithm)) {
            throw new IllegalStateException("JWKS key algorithm is not allowed for the token provider");
        }
        final var allowedAlgorithms =
                keyAlgorithm == null ? state.allowedAlgorithms().toArray(new String[0]) : new String[] {keyAlgorithm};
        return new JwtConsumerBuilder()
                .setRequireSubject()
                .setRequireExpirationTime()
                .setRequireJwtId()
                .setAllowedClockSkewInSeconds(CLOCK_SKEW_SECONDS)
                .setExpectedIssuer(state.issuer())
                .setExpectedAudience(true, state.expectedAudience())
                .setJwsAlgorithmConstraints(new AlgorithmConstraints(ConstraintType.PERMIT, allowedAlgorithms))
                .setVerificationKey(key.getKey())
                .build();
    }

    private TokenValidationResult buildValidResult(JwtClaims claims, IssuerState state) {
        try {
            final var subject = claims.getSubject();
            final var issuer = claims.getIssuer();
            final var expiration = claims.getExpirationTime();

            final var claimsMap = applyClaimsMapping(claims, state.claimsMapping());
            final var authenticatedAt = instantClaim(claimsMap.get("auth_time"));
            final var tokenId = Optional.ofNullable(claims.getJwtId()).filter(value -> !value.isBlank());
            final var assuranceLevel = Optional.ofNullable(claimsMap.get("acr"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(value -> !value.isBlank());

            return new TokenValidationResult.Valid(ValidatedIdentity.fromValidatedClaims(
                    state.providerId(),
                    subject,
                    issuer,
                    Set.copyOf(claims.getAudience()),
                    authenticatedAt,
                    tokenId,
                    claimsMap,
                    assuranceLevel,
                    Instant.ofEpochSecond(expiration.getValue())));
        } catch (MalformedClaimException e) {
            return new TokenValidationResult.Invalid("Malformed claims");
        }
    }

    private Optional<Instant> instantClaim(Object value) {
        try {
            if (value instanceof Number number) {
                return Optional.of(Instant.ofEpochSecond(number.longValue()));
            }
            return value == null
                    ? Optional.empty()
                    : Optional.of(Instant.ofEpochSecond(Long.parseLong(value.toString())));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
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
     * <p>The public-key thumbprint prevents reused key IDs from retaining old verification
     * material after a provider rotation.
     */
    private record ConsumerKey(
            String issuer,
            Set<String> audiences,
            Set<String> allowedAlgorithms,
            String keyAlgorithm,
            String publicKeyThumbprint) {}

    /**
     * Per-{@link TokenProviderConfig} pre-computed validator state. Builds the canonical
     * audiences set and {@code expectedAudience} array exactly once so the request hot
     * path can skip {@code Set.copyOf} + {@code toArray} on every authenticated call.
     */
    private record IssuerState(
            String providerId,
            String issuer,
            Set<String> audiences,
            String[] expectedAudience,
            Set<String> allowedAlgorithms,
            Map<String, String> claimsMapping) {

        static IssuerState of(TokenProviderConfig config) {
            if (config.audiences() == null || config.audiences().isEmpty()) {
                throw new IllegalStateException(
                        "Token provider '" + config.id() + "' has no audiences configured; refuse to validate.");
            }
            if (config.allowedAlgorithms() == null || config.allowedAlgorithms().isEmpty()) {
                throw new IllegalStateException(
                        "Token provider '" + config.id() + "' has no allowed algorithms configured.");
            }
            final var canonicalAudiences = Set.copyOf(config.audiences());
            final var expectedAudience = canonicalAudiences.toArray(new String[0]);
            final var canonicalAlgorithms = Set.copyOf(config.allowedAlgorithms());
            return new IssuerState(
                    config.id(),
                    config.issuer(),
                    canonicalAudiences,
                    expectedAudience,
                    canonicalAlgorithms,
                    config.claimsMapping());
        }
    }

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
