package aussie.core.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;

/**
 * Service for PKCE (Proof Key for Code Exchange) operations.
 *
 * <p>Implements RFC 7636 for protecting authorization code flows against
 * interception attacks. Only the S256 challenge method is supported as
 * the plain method provides insufficient security.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7636">RFC 7636</a>
 */
@ApplicationScoped
public class PkceService {

    private static final Logger LOG = Logger.getLogger(PkceService.class);
    private static final String S256_METHOD = "S256";
    private static final int VERIFIER_LENGTH = 64;
    private static final int STATE_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PkceChallengeRepository repository;
    private final PkceConfig config;

    @Inject
    public PkceService(PkceChallengeRepository repository, PkceConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /**
     * Check if PKCE is enabled.
     *
     * @return true if PKCE is enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Check if PKCE is required for all authorization requests.
     *
     * @return true if PKCE is required
     */
    public boolean isRequired() {
        return config.required();
    }

    /**
     * Validate the challenge method.
     *
     * <p>Only S256 is supported. The plain method is rejected as it provides
     * no security against interception attacks.
     *
     * @param method The challenge method from the request
     * @return true if the method is valid (S256)
     */
    public boolean isValidChallengeMethod(String method) {
        return S256_METHOD.equals(method);
    }

    /**
     * Generate a cryptographically secure code verifier.
     *
     * <p>Per RFC 7636, the verifier must be between 43-128 characters,
     * using unreserved characters (A-Z, a-z, 0-9, "-", ".", "_", "~").
     *
     * @return URL-safe base64 encoded random string
     */
    public String generateCodeVerifier() {
        byte[] randomBytes = new byte[VERIFIER_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Generate S256 challenge from verifier.
     *
     * <p>Computes: BASE64URL(SHA256(verifier))
     *
     * @param verifier The code verifier
     * @return Base64URL encoded SHA-256 hash of the verifier
     */
    public String generateChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the Java spec, so this should never happen
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Store a PKCE challenge for later verification.
     *
     * <p>The challenge is stored with the OAuth state parameter as the key.
     * It will automatically expire after the configured TTL.
     *
     * @param state The OAuth state parameter (must not be null or blank)
     * @param challenge The code_challenge value from the request (must not be null or blank)
     * @return Uni completing when the challenge is stored
     * @throws IllegalArgumentException if state or challenge is null or blank
     */
    public Uni<Void> storeChallenge(String state, String challenge) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be null or blank");
        }
        if (challenge == null || challenge.isBlank()) {
            throw new IllegalArgumentException("challenge must not be null or blank");
        }
        LOG.debugf("Storing PKCE challenge for state: %s", state);
        return repository.store(state, challenge, config.challengeTtl());
    }

    /**
     * Verify that a code verifier matches the stored challenge.
     *
     * <p>This method:
     * <ol>
     *   <li>Retrieves and deletes the stored challenge (one-time use)</li>
     *   <li>Computes SHA-256 hash of the provided verifier</li>
     *   <li>Compares against the stored challenge</li>
     * </ol>
     *
     * @param state The OAuth state parameter (must not be null or blank)
     * @param verifier The code_verifier from the token request (must not be null or blank)
     * @return Uni with true if verification succeeds, false otherwise
     * @throws IllegalArgumentException if state or verifier is null or blank
     */
    public Uni<Boolean> verifyChallenge(String state, String verifier) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be null or blank");
        }
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalArgumentException("verifier must not be null or blank");
        }
        LOG.debugf("Verifying PKCE challenge for state: %s", state);

        return repository.consumeChallenge(state).map(challengeOpt -> {
            if (challengeOpt.isEmpty()) {
                LOG.debugf("No PKCE challenge found for state: %s", state);
                return false;
            }

            String storedChallenge = challengeOpt.get();
            String computedChallenge = generateChallenge(verifier);
            boolean valid = storedChallenge.equals(computedChallenge);

            if (!valid) {
                LOG.debugf("PKCE challenge mismatch for state: %s", state);
            }

            return valid;
        });
    }

    /**
     * Generate a cryptographically secure state parameter.
     *
     * @return URL-safe base64 encoded random string
     */
    public String generateState() {
        final var bytes = new byte[STATE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
