package aussie.core.service.session;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;

import aussie.core.config.RouteAuthConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionToken;

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

    private final SessionConfig config;
    private final RouteAuthConfig routeAuthConfig;

    private PrivateKey signingKey;
    private boolean signingAvailable;

    @Inject
    public SessionTokenService(SessionConfig config, RouteAuthConfig routeAuthConfig) {
        this.config = config;
        this.routeAuthConfig = routeAuthConfig;
    }

    @PostConstruct
    void init() {
        // Try to load signing key from route auth config
        Optional<String> keyData = routeAuthConfig.jws().signingKey();
        if (keyData.isPresent()) {
            try {
                signingKey = SigningKeyRecord.parsePrivateKey(keyData.get());
                signingAvailable = true;
                LOG.info("Session token service initialized with signing key");
            } catch (IllegalArgumentException e) {
                LOG.warnf("Failed to load signing key for session tokens: %s", e.getMessage());
                signingAvailable = false;
            }
        } else {
            LOG.debug("No signing key configured for session tokens");
            signingAvailable = false;
        }
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

        if (!signingAvailable) {
            throw new IllegalStateException("JWS signing key not configured");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(config.jws().ttl());

        Map<String, Object> claimsMap = buildClaims(session, additionalClaims, now, expiresAt);

        try {
            String token = signToken(claimsMap, expiresAt);
            Set<String> includedClaims = new HashSet<>(claimsMap.keySet());

            LOG.debugf("Generated session token for session %s, expires at %s", session.id(), expiresAt);

            return new SessionToken(token, expiresAt, session.id(), includedClaims);
        } catch (JoseException e) {
            throw new SessionTokenException("Failed to sign session token", e);
        }
    }

    private String signToken(Map<String, Object> claims, Instant expiresAt) throws JoseException {
        JwtClaims jwtClaims = new JwtClaims();

        // Set standard claims
        jwtClaims.setIssuer((String) claims.get("iss"));
        jwtClaims.setSubject((String) claims.get("sub"));
        jwtClaims.setIssuedAt(org.jose4j.jwt.NumericDate.fromSeconds((Long) claims.get("iat")));
        jwtClaims.setExpirationTime(org.jose4j.jwt.NumericDate.fromSeconds((Long) claims.get("exp")));
        jwtClaims.setJwtId((String) claims.get("jti"));

        // Set optional audience
        if (claims.containsKey("aud")) {
            jwtClaims.setAudience((String) claims.get("aud"));
        }

        // Add all other claims
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            if (!isStandardClaim(key)) {
                jwtClaims.setClaim(key, entry.getValue());
            }
        }

        JsonWebSignature jws = new JsonWebSignature();
        jws.setPayload(jwtClaims.toJson());
        jws.setKey(signingKey);
        jws.setKeyIdHeaderValue(routeAuthConfig.jws().keyId());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private boolean isStandardClaim(String claimName) {
        return "iss".equals(claimName)
                || "sub".equals(claimName)
                || "iat".equals(claimName)
                || "exp".equals(claimName)
                || "jti".equals(claimName)
                || "aud".equals(claimName);
    }

    private Map<String, Object> buildClaims(
            Session session, Map<String, Object> additionalClaims, Instant now, Instant expiresAt) {

        Map<String, Object> claims = new HashMap<>();

        // Standard JWT claims
        claims.put("iss", config.jws().issuer());
        claims.put("sub", session.userId());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());

        // Add audience if configured
        config.jws().audience().ifPresent(aud -> claims.put("aud", aud));

        // Add session reference
        claims.put("sid", session.id());

        // Add configured claims from session
        List<String> includeClaims = config.jws().includeClaims();
        if (session.claims() != null) {
            for (String claimName : includeClaims) {
                if (session.claims().containsKey(claimName)) {
                    claims.put(claimName, session.claims().get(claimName));
                }
            }
        }

        // Add permissions/roles
        if (includeClaims.contains("roles") && session.permissions() != null) {
            claims.put("roles", session.permissions());
        }

        // Add email from claims if available
        if (includeClaims.contains("email") && session.claims() != null) {
            Object email = session.claims().get("email");
            if (email != null) {
                claims.put("email", email);
            }
        }

        // Add name from claims if available
        if (includeClaims.contains("name") && session.claims() != null) {
            Object name = session.claims().get("name");
            if (name != null) {
                claims.put("name", name);
            }
        }

        // Add any additional claims
        claims.putAll(additionalClaims);

        return claims;
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
        return signingAvailable;
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
