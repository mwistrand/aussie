package aussie.core.model.auth;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Identity established by a configured cryptographic token validator. */
public final class ValidatedIdentity {

    private final String providerId;
    private final String subject;
    private final String issuer;
    private final Set<String> audiences;
    private final Optional<Instant> authenticatedAt;
    private final Optional<String> tokenId;
    private final Map<String, Object> claims;
    private final Optional<String> assuranceLevel;
    private final Instant expiresAt;

    private ValidatedIdentity(
            String providerId,
            String subject,
            String issuer,
            Set<String> audiences,
            Optional<Instant> authenticatedAt,
            Optional<String> tokenId,
            Map<String, Object> claims,
            Optional<String> assuranceLevel,
            Instant expiresAt) {
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
        this.providerId = providerId;
        this.subject = subject;
        this.issuer = issuer;
        this.audiences = audiences;
        this.authenticatedAt = authenticatedAt;
        this.tokenId = tokenId;
        this.claims = claims;
        this.assuranceLevel = assuranceLevel;
        this.expiresAt = expiresAt;
    }

    /**
     * Creates an identity after a configured validator has verified the token and its claims.
     * Callers must not use this for unvalidated input.
     */
    public static ValidatedIdentity fromValidatedClaims(
            String providerId,
            String subject,
            String issuer,
            Set<String> audiences,
            Optional<Instant> authenticatedAt,
            Optional<String> tokenId,
            Map<String, Object> claims,
            Optional<String> assuranceLevel,
            Instant expiresAt) {
        return new ValidatedIdentity(
                providerId, subject, issuer, audiences, authenticatedAt, tokenId, claims, assuranceLevel, expiresAt);
    }

    public String providerId() {
        return providerId;
    }

    public String subject() {
        return subject;
    }

    public String issuer() {
        return issuer;
    }

    public Set<String> audiences() {
        return audiences;
    }

    public Optional<Instant> authenticatedAt() {
        return authenticatedAt;
    }

    public Optional<String> tokenId() {
        return tokenId;
    }

    public Map<String, Object> claims() {
        return claims;
    }

    public Optional<String> assuranceLevel() {
        return assuranceLevel;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public ValidatedIdentity withClaims(Map<String, Object> claims) {
        return fromValidatedClaims(
                providerId, subject, issuer, audiences, authenticatedAt, tokenId, claims, assuranceLevel, expiresAt);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ValidatedIdentity that)) {
            return false;
        }
        return providerId.equals(that.providerId)
                && subject.equals(that.subject)
                && issuer.equals(that.issuer)
                && audiences.equals(that.audiences)
                && authenticatedAt.equals(that.authenticatedAt)
                && tokenId.equals(that.tokenId)
                && claims.equals(that.claims)
                && assuranceLevel.equals(that.assuranceLevel)
                && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                providerId, subject, issuer, audiences, authenticatedAt, tokenId, claims, assuranceLevel, expiresAt);
    }

    @Override
    public String toString() {
        return "ValidatedIdentity[providerId="
                + providerId
                + ", subject="
                + subject
                + ", issuer="
                + issuer
                + ", audiences="
                + audiences
                + ", authenticatedAt="
                + authenticatedAt
                + ", tokenId="
                + tokenId
                + ", claims="
                + claims
                + ", assuranceLevel="
                + assuranceLevel
                + ", expiresAt="
                + expiresAt
                + "]";
    }
}
