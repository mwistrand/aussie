package aussie.core.model.auth;

/**
 * Result of validating an incoming bearer token.
 */
public sealed interface TokenValidationResult {

    /**
     * Token was successfully validated.
     *
     * @param identity immutable identity and validation provenance
     */
    record Valid(ValidatedIdentity identity) implements TokenValidationResult {
        /**
         * Compatibility constructor for validator providers compiled against the original result shape.
         */
        public Valid(String subject, String issuer, java.util.Map<String, Object> claims, java.time.Instant expiresAt) {
            this(new ValidatedIdentity(
                    "legacy",
                    subject,
                    issuer,
                    java.util.Set.of(),
                    java.util.Optional.empty(),
                    java.util.Optional.ofNullable(claims == null ? null : claims.get("jti"))
                            .map(Object::toString),
                    claims,
                    java.util.Optional.empty(),
                    expiresAt));
        }

        public Valid {
            if (identity == null) {
                throw new IllegalArgumentException("Validated identity cannot be null");
            }
        }

        public String subject() {
            return identity.subject();
        }

        public String issuer() {
            return identity.issuer();
        }

        public java.util.Map<String, Object> claims() {
            return identity.claims();
        }

        public java.time.Instant expiresAt() {
            return identity.expiresAt();
        }
    }

    /**
     * Token validation failed.
     *
     * @param reason description of why validation failed
     */
    record Invalid(String reason) implements TokenValidationResult {}

    /**
     * No token was provided in the request.
     */
    record NoToken() implements TokenValidationResult {}
}
