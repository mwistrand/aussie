package aussie.core.model.auth;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Identity established by a configured cryptographic token validator. */
public record ValidatedIdentity(
        String providerId,
        String subject,
        String issuer,
        Set<String> audiences,
        Optional<Instant> authenticatedAt,
        Optional<String> tokenId,
        Map<String, Object> claims,
        Optional<String> assuranceLevel,
        Instant expiresAt) {

    public ValidatedIdentity {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID cannot be null or blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject cannot be null or blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("Issuer cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiration cannot be null");
        }
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
        authenticatedAt = authenticatedAt == null ? Optional.empty() : authenticatedAt;
        tokenId = tokenId == null ? Optional.empty() : tokenId;
        claims = claims == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(claims));
        assuranceLevel = assuranceLevel == null ? Optional.empty() : assuranceLevel;
    }

    public ValidatedIdentity withClaims(Map<String, Object> claims) {
        return new ValidatedIdentity(
                providerId, subject, issuer, audiences, authenticatedAt, tokenId, claims, assuranceLevel, expiresAt);
    }
}
