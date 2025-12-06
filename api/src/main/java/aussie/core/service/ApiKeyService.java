package aussie.core.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.config.ApiKeyConfig;
import aussie.core.model.ApiKey;
import aussie.core.model.ApiKeyCreateResult;
import aussie.core.port.in.ApiKeyManagement;
import aussie.core.port.out.ApiKeyRepository;

/**
 * Service for managing API keys used for authentication.
 *
 * <p>Handles key generation, hashing, validation, and revocation. Keys are
 * stored as SHA-256 hashes; the plaintext is only returned once at creation.
 */
@ApplicationScoped
public class ApiKeyService implements ApiKeyManagement {

    private static final int KEY_LENGTH_BYTES = 32;
    private static final int KEY_ID_LENGTH = 8;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final ApiKeyConfig config;

    @Inject
    public ApiKeyService(ApiKeyRepository repository, ApiKeyConfig config) {
        this.repository = repository;
        this.config = config;
    }

    @Override
    public ApiKeyCreateResult create(String name, String description, Set<String> permissions, Duration ttl) {
        // Validate TTL against configured maximum
        validateTtl(ttl);

        String keyId = generateKeyId();
        String plaintextKey = generateSecureKey();
        String keyHash = hashKey(plaintextKey);

        Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;

        var apiKey = ApiKey.builder(keyId, keyHash)
                .name(name)
                .description(description)
                .permissions(permissions != null ? permissions : Set.of())
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        // Save synchronously (blocking) for simplicity in the create flow
        repository.save(apiKey).await().indefinitely();

        return new ApiKeyCreateResult(keyId, plaintextKey, apiKey);
    }

    @Override
    public Optional<ApiKey> validate(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return Optional.empty();
        }

        String keyHash = hashKey(plaintextKey);
        return repository.findByHash(keyHash).await().indefinitely().filter(ApiKey::isValid);
    }

    @Override
    public List<ApiKey> list() {
        return repository.findAll().await().indefinitely().stream()
                .map(ApiKey::redacted)
                .toList();
    }

    @Override
    public boolean revoke(String keyId) {
        var existingKey = repository.findById(keyId).await().indefinitely();
        if (existingKey.isEmpty()) {
            return false;
        }

        var revokedKey = existingKey.get().revoke();
        repository.save(revokedKey).await().indefinitely();
        return true;
    }

    @Override
    public Optional<ApiKey> get(String keyId) {
        return repository.findById(keyId).await().indefinitely().map(ApiKey::redacted);
    }

    /**
     * Generates a cryptographically secure random key.
     *
     * @return Base64URL-encoded key string (43 characters)
     */
    private String generateSecureKey() {
        byte[] bytes = new byte[KEY_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Generates a short random key ID for display and revocation.
     *
     * @return 8-character hex string
     */
    private String generateKeyId() {
        byte[] bytes = new byte[KEY_ID_LENGTH / 2];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Computes SHA-256 hash of the plaintext key.
     *
     * @param key the plaintext key
     * @return hex-encoded hash string
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Validates the requested TTL against the configured maximum.
     *
     * @param ttl the requested TTL (may be null for no expiration)
     * @throws IllegalArgumentException if TTL exceeds the configured maximum
     */
    private void validateTtl(Duration ttl) {
        if (ttl == null) {
            // No TTL requested - check if max TTL is configured
            config.maxTtl().ifPresent(maxTtl -> {
                throw new IllegalArgumentException("TTL is required. Maximum allowed: " + formatDuration(maxTtl));
            });
            return;
        }

        config.maxTtl().ifPresent(maxTtl -> {
            if (ttl.compareTo(maxTtl) > 0) {
                throw new IllegalArgumentException("TTL exceeds maximum allowed. Requested: " + formatDuration(ttl)
                        + ", Maximum: " + formatDuration(maxTtl));
            }
        });
    }

    /**
     * Formats a duration for human-readable display.
     */
    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + " days";
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " hours";
        }
        return duration.toMinutes() + " minutes";
    }
}
