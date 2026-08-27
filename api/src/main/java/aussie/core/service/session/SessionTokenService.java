package aussie.core.service.session;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionToken;
import aussie.core.service.auth.IssuedClaimPolicy;
import aussie.core.service.auth.SigningKeyRegistry;
import aussie.core.util.SafeLogging;

/**
 * Service for generating JWS tokens from sessions.
 *
 * <p>Session tokens are short-lived JWS tokens that Aussie includes in
 * requests forwarded to downstream services. This allows downstream
 * services to verify the user's identity without accessing session storage.
 */
@ApplicationScoped
public class SessionTokenService {

    private static final Logger LOG = Logger.getLogger(SessionTokenService.class);
    private static final String TOKEN_PROFILE = "aussie+session-jwt-v1";

    private final SessionConfig config;
    private final SigningKeyRegistry keyRegistry;

    @Inject
    public SessionTokenService(SessionConfig config, SigningKeyRegistry keyRegistry) {
        this.config = config;
        this.keyRegistry = keyRegistry;
    }

    /**
     * Generate a JWS token from a session.
     *
     * @param session The session to generate a token for
     * @return The generated session token
     */
    public SessionToken generateToken(Session session) {
        return generateToken(session, Map.of());
    }

    /**
     * Generate a JWS token from a session with additional claims.
     *
     * @param session The session to generate a token for
     * @param additionalClaims Additional claims to include
     * @return The generated session token
     */
    public SessionToken generateToken(Session session, Map<String, Object> additionalClaims) {
        if (!config.jws().enabled()) {
            throw new IllegalStateException("JWS token generation is disabled");
        }

        final SigningKeyRecord signingKey;
        try {
            signingKey = keyRegistry.getCurrentSigningKey();
        } catch (IllegalStateException e) {
            throw new SessionTokenException("JWS signing key not configured", e);
        }
        final var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (session.expiresAt() == null) {
            throw new SessionTokenException("Session has no expiration");
        }
        final var expiresAt =
                earliest(now.plus(config.jws().ttl()), session.expiresAt()).truncatedTo(ChronoUnit.SECONDS);
        if (!expiresAt.isAfter(now)) {
            throw new SessionTokenException("Session has expired");
        }

        final var claimsMap = buildClaims(session, additionalClaims, now, expiresAt);

        try {
            final var token = signToken(claimsMap, signingKey);
            final var includedClaims = new HashSet<>(claimsMap.keySet());

            if (LOG.isDebugEnabled()) {
                LOG.debugf(
                        "Generated session token for session_hash=%s, expires at %s",
                        SafeLogging.identifier(session.id()), expiresAt);
            }

            return new SessionToken(token, expiresAt, session.id(), includedClaims);
        } catch (JoseException e) {
            throw new SessionTokenException("Failed to sign session token", e);
        }
    }

    private String signToken(Map<String, Object> claims, SigningKeyRecord signingKey) throws JoseException {
        final var jwtClaims = new JwtClaims();

        // Set standard claims
        jwtClaims.setIssuer((String) claims.get("iss"));
        jwtClaims.setSubject((String) claims.get("sub"));
        jwtClaims.setIssuedAt(org.jose4j.jwt.NumericDate.fromSeconds((Long) claims.get("iat")));
        jwtClaims.setNotBefore(org.jose4j.jwt.NumericDate.fromSeconds((Long) claims.get("nbf")));
        jwtClaims.setExpirationTime(org.jose4j.jwt.NumericDate.fromSeconds((Long) claims.get("exp")));
        jwtClaims.setJwtId((String) claims.get("jti"));

        // Set optional audience
        if (claims.containsKey("aud")) {
            jwtClaims.setAudience((String) claims.get("aud"));
        }

        // Add all other claims
        for (final var entry : claims.entrySet()) {
            final var key = entry.getKey();
            if (!isStandardClaim(key)) {
                jwtClaims.setClaim(key, entry.getValue());
            }
        }

        final var jws = new JsonWebSignature();
        jws.setPayload(jwtClaims.toJson());
        jws.setKey(signingKey.privateKey());
        jws.setKeyIdHeaderValue(signingKey.keyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private boolean isStandardClaim(String claimName) {
        return "iss".equals(claimName)
                || "sub".equals(claimName)
                || "iat".equals(claimName)
                || "nbf".equals(claimName)
                || "exp".equals(claimName)
                || "jti".equals(claimName)
                || "aud".equals(claimName);
    }

    private Map<String, Object> buildClaims(
            Session session, Map<String, Object> additionalClaims, Instant now, Instant expiresAt) {

        final var claims = new HashMap<String, Object>();

        // Standard JWT claims
        claims.put("iss", config.jws().issuer());
        claims.put("sub", session.userId());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());

        // Add audience if configured
        config.jws().audience().ifPresent(aud -> claims.put("aud", aud));

        // Add session reference
        claims.put("sid", session.id());
        claims.put("original_iss", session.issuer());
        claims.put("aussie_token_profile", TOKEN_PROFILE);

        // Add configured claims from session
        final var includeClaims = config.jws().includeClaims();
        if (session.claims() != null) {
            for (String claimName : includeClaims) {
                if (!isServerOwnedClaim(claimName) && session.claims().containsKey(claimName)) {
                    claims.put(claimName, session.claims().get(claimName));
                }
            }
        }

        // Add permissions/roles
        if (includeClaims.contains("roles") && session.permissions() != null) {
            claims.put("roles", session.permissions());
        }

        additionalClaims.forEach((name, value) -> {
            if (!isServerOwnedClaim(name)) {
                claims.put(name, value);
            }
        });

        if (claims.entrySet().stream()
                .anyMatch(entry -> !IssuedClaimPolicy.isAllowed(entry.getKey(), entry.getValue()))) {
            throw new SessionTokenException("Session claim violates the issued-token policy");
        }

        return claims;
    }

    private boolean isServerOwnedClaim(String claimName) {
        return isStandardClaim(claimName)
                || "sid".equals(claimName)
                || "original_iss".equals(claimName)
                || "aussie_token_profile".equals(claimName);
    }

    private Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    /**
     * Check if JWS token generation is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.jws().enabled();
    }

    /**
     * Check if token signing is available.
     *
     * @return true if a signing key is configured
     */
    public boolean isSigningAvailable() {
        return keyRegistry.isReady();
    }

    /**
     * Exception thrown when session token generation fails.
     */
    public static class SessionTokenException extends RuntimeException {
        public SessionTokenException(String message) {
            super(message);
        }

        public SessionTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
