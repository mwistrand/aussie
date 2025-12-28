package aussie.core.model.auth;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Signing key with metadata for lifecycle management.
 *
 * <p>
 * Each key has a unique ID (kid) used in JWT headers for key selection
 * during verification. The lifecycle timestamps track when the key
 * transitioned between states.
 *
 * @param keyId        Unique key identifier (used as JWT 'kid' header)
 * @param privateKey   RSA private key for signing (null for verification-only
 *                     keys)
 * @param publicKey    RSA public key for verification and JWKS exposure
 * @param status       Current lifecycle status
 * @param createdAt    When the key was created
 * @param activatedAt  When the key became ACTIVE (null if PENDING)
 * @param deprecatedAt When the key became DEPRECATED (null if not deprecated)
 * @param retiredAt    When the key became RETIRED (null if not retired)
 */
public record SigningKeyRecord(
        String keyId,
        RSAPrivateKey privateKey,
        RSAPublicKey publicKey,
        KeyStatus status,
        Instant createdAt,
        Instant activatedAt,
        Instant deprecatedAt,
        Instant retiredAt) {

    public SigningKeyRecord {
        Objects.requireNonNull(keyId, "keyId is required");
        Objects.requireNonNull(publicKey, "publicKey is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
    }

    /**
     * Create a new pending key.
     */
    public static SigningKeyRecord pending(String keyId, RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        return new SigningKeyRecord(keyId, privateKey, publicKey, KeyStatus.PENDING, Instant.now(), null, null, null);
    }

    /**
     * Create a new active key.
     */
    public static SigningKeyRecord active(String keyId, RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        var now = Instant.now();
        return new SigningKeyRecord(keyId, privateKey, publicKey, KeyStatus.ACTIVE, now, now, null, null);
    }

    /**
     * Transition this key to ACTIVE status.
     */
    public SigningKeyRecord activate(Instant activatedAt) {
        if (status != KeyStatus.PENDING) {
            throw new IllegalStateException("Can only activate PENDING keys, current status: " + status);
        }
        if (activatedAt == null) {
            activatedAt = Instant.now();
        }
        return new SigningKeyRecord(keyId, privateKey, publicKey, KeyStatus.ACTIVE, createdAt, activatedAt, null, null);
    }

    /**
     * Transition this key to DEPRECATED status.
     */
    public SigningKeyRecord deprecate(Instant deprecatedAt) {
        if (status != KeyStatus.ACTIVE) {
            throw new IllegalStateException("Can only deprecate ACTIVE keys, current status: " + status);
        }
        if (deprecatedAt == null) {
            deprecatedAt = Instant.now();
        }
        return new SigningKeyRecord(
                keyId, privateKey, publicKey, KeyStatus.DEPRECATED, createdAt, activatedAt, deprecatedAt, null);
    }

    /**
     * Transition this key to RETIRED status.
     */
    public SigningKeyRecord retire(Instant retiredAt) {
        if (status != KeyStatus.DEPRECATED && status != KeyStatus.ACTIVE) {
            throw new IllegalStateException("Can only retire ACTIVE or DEPRECATED keys, current status: " + status);
        }
        if (retiredAt == null) {
            retiredAt = Instant.now();
        }
        return new SigningKeyRecord(
                keyId,
                privateKey,
                publicKey,
                KeyStatus.RETIRED,
                createdAt,
                activatedAt,
                status == KeyStatus.DEPRECATED ? deprecatedAt : retiredAt,
                retiredAt);
    }

    /**
     * Check if this key can be used for signing (has private key and is ACTIVE).
     */
    public boolean canSign() {
        return privateKey != null && status == KeyStatus.ACTIVE;
    }

    /**
     * Check if this key can be used for verification (ACTIVE or DEPRECATED).
     */
    public boolean canVerify() {
        return status == KeyStatus.ACTIVE || status == KeyStatus.DEPRECATED;
    }

    /**
     * Create a verification-only copy without the private key.
     */
    public SigningKeyRecord withoutPrivateKey() {
        return new SigningKeyRecord(keyId, null, publicKey, status, createdAt, activatedAt, deprecatedAt, retiredAt);
    }

    /**
     * Parse an RSA private key from PEM or base64-encoded PKCS8 format.
     *
     * @param keyData PEM-encoded or raw base64 PKCS8 private key
     * @return the parsed RSA private key
     * @throws IllegalArgumentException if the key data is invalid
     */
    public static RSAPrivateKey parsePrivateKey(String keyData) {
        try {
            final var keyContent = keyData.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            final var keyBytes = Base64.getDecoder().decode(keyContent);
            final var keySpec = new PKCS8EncodedKeySpec(keyBytes);
            final var keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse RSA private key", e);
        }
    }

    /**
     * Derive the public key from an RSA private key.
     *
     * <p>RSA private keys in PKCS8 format contain the public key components.
     *
     * @param privateKey the RSA private key (must be RSAPrivateCrtKey)
     * @return the derived RSA public key
     * @throws IllegalArgumentException if the public key cannot be derived
     */
    public static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        try {
            final var keyFactory = KeyFactory.getInstance("RSA");

            if (privateKey instanceof RSAPrivateCrtKey crtKey) {
                final var publicKeySpec = new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent());
                return (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
            }

            throw new IllegalArgumentException("Cannot derive public key from non-CRT private key");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to derive RSA public key", e);
        }
    }
}
