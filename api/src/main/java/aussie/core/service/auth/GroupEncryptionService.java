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

import aussie.core.model.auth.Group;

/**
 * Encryption service for Group records at rest.
 *
 * <p>Uses AES-256-GCM encryption with unique IVs per operation. This provides
 * both confidentiality and integrity for stored group data.
 *
 * <p>Shares the same encryption key as API keys for simplicity.
 *
 * <h2>Configuration</h2>
 * <pre>
 * aussie.auth.encryption.key=${AUTH_ENCRYPTION_KEY}  # Base64-encoded 256-bit key
 * aussie.auth.encryption.key-id=v1                    # For future key rotation
 * </pre>
 */
@ApplicationScoped
public class GroupEncryptionService {

    private static final Logger LOG = Logger.getLogger(GroupEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String FIELD_SEPARATOR = "\u0000";

    private final SecretKey secretKey;
    private final String keyId;
    private final boolean encryptionEnabled;
    private final SecureRandom secureRandom;

    /**
     * CDI constructor.
     *
     * @param encryptionKey optional base64-encoded encryption key from config
     * @param keyId         key identifier for rotation support
     */
    @Inject
    public GroupEncryptionService(
            @ConfigProperty(name = "aussie.auth.encryption.key") Optional<String> encryptionKey,
            @ConfigProperty(name = "aussie.auth.encryption.key-id", defaultValue = "v1") String keyId) {
        this(encryptionKey, keyId, true);
    }

    /**
     * Constructor for manual instantiation (used by storage providers).
     *
     * @param encryptionKey optional base64-encoded encryption key
     * @param keyId         key identifier for rotation support
     */
    public GroupEncryptionService(Optional<String> encryptionKey, String keyId, boolean logStatus) {
        this.keyId = keyId;
        this.secureRandom = new SecureRandom();

        if (encryptionKey.isPresent() && !encryptionKey.get().isBlank()) {
            final byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.get());
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "Encryption key must be 256 bits (32 bytes). Got: " + keyBytes.length + " bytes");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.encryptionEnabled = true;
            if (logStatus) {
                LOG.info("Group encryption enabled with key ID: " + keyId);
            }
        } else {
            this.secretKey = null;
            this.encryptionEnabled = false;
            if (logStatus) {
                LOG.warn("Group encryption is DISABLED. Set aussie.auth.encryption.key to enable.");
            }
        }
    }

    /**
     * Encrypt a Group record for storage.
     *
     * <p>If encryption is disabled, returns a plaintext-compatible format.
     *
     * @param group the group to encrypt
     * @return Base64-encoded encrypted data, or plaintext if disabled
     */
    public String encrypt(Group group) {
        final String serialized = serialize(group);

        if (!encryptionEnabled) {
            return "PLAIN:" + Base64.getEncoder().encodeToString(serialized.getBytes(StandardCharsets.UTF_8));
        }

        try {
            final byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            final Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            final byte[] ciphertext = cipher.doFinal(serialized.getBytes(StandardCharsets.UTF_8));
            final byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);

            final ByteBuffer buffer = ByteBuffer.allocate(1 + keyIdBytes.length + IV_LENGTH + ciphertext.length);
            buffer.put((byte) keyIdBytes.length);
            buffer.put(keyIdBytes);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt group", e);
        }
    }

    /**
     * Decrypt a Group record from storage.
     *
     * @param encryptedData Base64-encoded encrypted data
     * @return the decrypted Group
     * @throws RuntimeException if decryption fails
     */
    public Group decrypt(String encryptedData) {
        if (encryptedData.startsWith("PLAIN:")) {
            final String encoded = encryptedData.substring(6);
            final String serialized = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return deserialize(serialized);
        }

        if (!encryptionEnabled) {
            throw new IllegalStateException("Cannot decrypt data: encryption is disabled but data appears encrypted");
        }

        try {
            final byte[] data = Base64.getDecoder().decode(encryptedData);
            final ByteBuffer buffer = ByteBuffer.wrap(data);

            final int keyIdLength = buffer.get() & 0xFF;
            final byte[] keyIdBytes = new byte[keyIdLength];
            buffer.get(keyIdBytes);
            final String dataKeyId = new String(keyIdBytes, StandardCharsets.UTF_8);

            if (!this.keyId.equals(dataKeyId)) {
                LOG.warnf("Key ID mismatch: expected %s, got %s. Key rotation may be needed.", this.keyId, dataKeyId);
            }

            final byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            final byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            final Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            final byte[] plaintext = cipher.doFinal(ciphertext);

            return deserialize(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt group", e);
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

    private String serialize(Group group) {
        return String.join(
                FIELD_SEPARATOR,
                group.id(),
                group.displayName(),
                group.description() != null ? group.description() : "",
                String.join(",", group.permissions()),
                group.createdAt().toString(),
                group.updatedAt().toString());
    }

    private Group deserialize(String data) {
        final String[] parts = data.split(FIELD_SEPARATOR, -1);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid serialized Group format");
        }

        final Set<String> permissions = parts[3].isEmpty() ? Set.of() : Set.of(parts[3].split(","));

        return Group.builder(parts[0])
                .displayName(parts[1])
                .description(parts[2].isEmpty() ? null : parts[2])
                .permissions(permissions)
                .createdAt(Instant.parse(parts[4]))
                .updatedAt(Instant.parse(parts[5]))
                .build();
    }
}
