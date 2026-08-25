package aussie.core.model.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A signed JWS token issued by Aussie for backend services.
 *
 * <p>The JWS may be empty while an issuance attempt is being represented internally,
 * but such a value must never be used to authorize or forward a protected route.
 *
 * @param jws       the signed JWT string (to be used as Bearer token), may be empty
 * @param subject   the subject claim from the original token
 * @param expiresAt when this token expires
 * @param claims    forwarded claims from the original token
 * @param identityTokenId JWT ID of the original identity token
 * @param identityIssuedAt issued-at time of the original identity token
 */
public record AussieToken(
        String jws,
        String subject,
        Instant expiresAt,
        Map<String, Object> claims,
        Optional<String> identityTokenId,
        Optional<Instant> identityIssuedAt) {
    public AussieToken(String jws, String subject, Instant expiresAt, Map<String, Object> claims) {
        this(jws, subject, expiresAt, claims, Optional.empty(), Optional.empty());
    }

    public AussieToken {
        if (jws == null) {
            jws = "";
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("ExpiresAt cannot be null");
        }
        if (claims == null) {
            claims = Map.of();
        }
        identityTokenId = identityTokenId == null ? Optional.empty() : identityTokenId;
        identityIssuedAt = identityIssuedAt == null ? Optional.empty() : identityIssuedAt;
    }

    /**
     * Check if this token has a valid JWS to forward to backends.
     *
     * @return true if the JWS is present and non-empty
     */
    public boolean hasToken() {
        return !jws.isBlank();
    }
}
