package aussie.core.service.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import aussie.core.model.auth.ApiKey;

/**
 * Encryption service for API key records at rest.
 *
 * <p>Uses AES-256-GCM encryption with unique IVs per operation. This provides
 * both confidentiality and integrity for stored API key data.
 *
 * <h2>Configuration</h2>
 * <pre>
 * aussie.auth.encryption.key=${AUTH_ENCRYPTION_KEY}  # Base64-encoded 256-bit key
 * aussie.auth.encryption.key-id=v1                    # For future key rotation
 * </pre>
 *
 * <h2>Encrypted Data Format</h2>
 * <pre>
 * [keyIdLength (1 byte)][keyId (variable)][IV (12 bytes)][ciphertext][authTag (16 bytes)]
 * </pre>
 */
@ApplicationScoped
public class ApiKeyEncryptionService {

    private static final Logger LOG = Logger.getLogger(ApiKeyEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String FIELD_SEPARATOR = "\u0000";

    private static final String PROD_PROFILE = "prod";

    private final SecretKey secretKey;
    private final String keyId;
    private final boolean encryptionEnabled;
    private final boolean allowPlaintextReads;
    private final SecureRandom secureRandom;

    @Inject
    public ApiKeyEncryptionService(
            @ConfigProperty(name = "aussie.auth.encryption.key") Optional<String> encryptionKey,
            @ConfigProperty(name = "aussie.auth.encryption.key-id", defaultValue = "v1") String keyId,
            @ConfigProperty(name = "quarkus.profile", defaultValue = "") String profile,
            @ConfigProperty(name = "aussie.auth.encryption.allow-plaintext-reads", defaultValue = "false")
                    boolean allowPlaintextReads) {
        this.keyId = keyId;
        this.secureRandom = new SecureRandom();

        final boolean keyPresent =
                encryptionKey.isPresent() && !encryptionKey.get().isBlank();

        if (!keyPresent && isProd(profile)) {
            throw new IllegalStateException("aussie.auth.encryption.key must be set under prod profile. "
                    + "Use ApiKeyEncryptionService.generateKey() to mint a 256-bit key, "
                    + "store it in your secrets manager, and inject via AUTH_ENCRYPTION_KEY.");
        }

        if (keyPresent) {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.get());
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "Encryption key must be 256 bits (32 bytes). Got: " + keyBytes.length + " bytes");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.encryptionEnabled = true;
            LOG.info("API key encryption enabled with key ID: " + keyId);
        } else {
            this.secretKey = null;
            this.encryptionEnabled = false;
            LOG.warn("API key encryption is DISABLED (non-prod profile). "
                    + "Set aussie.auth.encryption.key to enable.");
        }

        // When encryption is disabled the service only writes PLAIN: records, so reads of those
        // same records must be permitted regardless of the flag — gating them would render the
        // service unable to read its own writes. The flag stays meaningful when encryption is
        // enabled: it controls whether legacy PLAIN: rows are still readable during migration.
        this.allowPlaintextReads = allowPlaintextReads || !this.encryptionEnabled;
        if (this.encryptionEnabled && allowPlaintextReads) {
            LOG.warn("aussie.auth.encryption.allow-plaintext-reads=true; existing PLAIN: records "
                    + "will still be readable. Disable once migration is complete.");
        } else if (!this.encryptionEnabled && !allowPlaintextReads) {
            LOG.warn("aussie.auth.encryption.allow-plaintext-reads=false was ignored because "
                    + "encryption is disabled; PLAIN: reads are forced on so the service can "
                    + "read its own writes. Set aussie.auth.encryption.key to enforce the flag.");
        }
    }

    private static boolean isProd(String profile) {
        if (profile == null) {
            return false;
        }
        for (String name : profile.split(",")) {
            if (PROD_PROFILE.equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Encrypt an API key record for storage.
     *
     * <p>If encryption is disabled, returns a plaintext-compatible format.
     *
     * @param apiKey the API key to encrypt
     * @return Base64-encoded encrypted data, or plaintext if disabled
     */
    public String encrypt(ApiKey apiKey) {
        String serialized = serialize(apiKey);

        if (!encryptionEnabled) {
            // Return plaintext marker + serialized data
            return "PLAIN:" + Base64.getEncoder().encodeToString(serialized.getBytes(StandardCharsets.UTF_8));
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(serialized.getBytes(StandardCharsets.UTF_8));
            byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);

            // Pack: [keyIdLength][keyId][IV][ciphertext+authTag]
            ByteBuffer buffer = ByteBuffer.allocate(1 + keyIdBytes.length + IV_LENGTH + ciphertext.length);
            buffer.put((byte) keyIdBytes.length);
            buffer.put(keyIdBytes);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    /**
     * Decrypt an API key record from storage.
     *
     * @param encryptedData Base64-encoded encrypted data
     * @return the decrypted API key
     * @throws RuntimeException if decryption fails
     */
    public ApiKey decrypt(String encryptedData) {
        if (encryptedData.startsWith("PLAIN:")) {
            if (!allowPlaintextReads) {
                throw new IllegalStateException("Refusing to decrypt PLAIN:-prefixed record: "
                        + "aussie.auth.encryption.allow-plaintext-reads is false. "
                        + "Re-encrypt this record or enable the migration flag.");
            }
            String encoded = encryptedData.substring(6);
            String serialized = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return deserialize(serialized);
        }

        if (!encryptionEnabled) {
            throw new IllegalStateException("Cannot decrypt data: encryption is disabled but data appears encrypted");
        }

        try {
            byte[] data = Base64.getDecoder().decode(encryptedData);
            ByteBuffer buffer = ByteBuffer.wrap(data);

            // Extract key ID
            int keyIdLength = buffer.get() & 0xFF;
            byte[] keyIdBytes = new byte[keyIdLength];
            buffer.get(keyIdBytes);
            String dataKeyId = new String(keyIdBytes, StandardCharsets.UTF_8);

            // Verify key ID matches (for future key rotation support)
            if (!this.keyId.equals(dataKeyId)) {
                LOG.warnf("Key ID mismatch: expected %s, got %s. Key rotation may be needed.", this.keyId, dataKeyId);
            }

            // Extract IV and ciphertext
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            // Decrypt
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return deserialize(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    /**
     * Check if encryption is enabled.
     *
     * @return true if encryption key is configured
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    /**
     * Serialize an ApiKey to a string format.
     */
    private String serialize(ApiKey apiKey) {
        return String.join(
                FIELD_SEPARATOR,
                apiKey.id(),
                apiKey.keyHash(),
                apiKey.name(),
                apiKey.description() != null ? apiKey.description() : "",
                String.join(",", apiKey.permissions()),
                apiKey.createdBy() != null ? apiKey.createdBy() : "",
                apiKey.createdAt().toString(),
                apiKey.expiresAt() != null ? apiKey.expiresAt().toString() : "",
                String.valueOf(apiKey.revoked()),
                apiKey.teamId() != null ? apiKey.teamId() : "");
    }

    /**
     * Deserialize a string to an ApiKey.
     *
     * <p>Supports backward compatibility:
     * <ul>
     *   <li>8 fields: original format (no createdBy, no teamId)</li>
     *   <li>9 fields: added createdBy</li>
     *   <li>10 fields: added teamId</li>
     * </ul>
     */
    private ApiKey deserialize(String data) {
        String[] parts = data.split(FIELD_SEPARATOR, -1);
        if (parts.length < 8) {
            throw new IllegalArgumentException("Invalid serialized ApiKey format");
        }

        // Handle backward compatibility: 8 fields (v1), 9 fields (v2 +createdBy), 10 fields (v3 +teamId)
        boolean hasCreatedBy = parts.length >= 9;
        boolean hasTeamId = parts.length >= 10;
        int permissionsIdx = 4;
        int createdByIdx = hasCreatedBy ? 5 : -1;
        int createdAtIdx = hasCreatedBy ? 6 : 5;
        int expiresAtIdx = hasCreatedBy ? 7 : 6;
        int revokedIdx = hasCreatedBy ? 8 : 7;
        int teamIdIdx = hasTeamId ? 9 : -1;

        Set<String> permissions = parts[permissionsIdx].isEmpty() ? Set.of() : Set.of(parts[permissionsIdx].split(","));
        String createdBy = hasCreatedBy && !parts[createdByIdx].isEmpty() ? parts[createdByIdx] : null;
        Instant expiresAt = parts[expiresAtIdx].isEmpty() ? null : Instant.parse(parts[expiresAtIdx]);
        String teamId = hasTeamId && !parts[teamIdIdx].isEmpty() ? parts[teamIdIdx] : null;

        return ApiKey.builder(parts[0], parts[1])
                .name(parts[2])
                .description(parts[3])
                .teamId(teamId)
                .permissions(permissions)
                .createdBy(createdBy)
                .createdAt(Instant.parse(parts[createdAtIdx]))
                .expiresAt(expiresAt)
                .revoked(Boolean.parseBoolean(parts[revokedIdx]))
                .build();
    }

    /**
     * Generate a new encryption key.
     *
     * <p>Utility method for generating keys during initial setup.
     *
     * @return Base64-encoded 256-bit key
     */
    public static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
