package aussie.core.service.auth;

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

import io.smallrye.mutiny.Uni;

import aussie.core.config.ApiKeyConfig;
import aussie.core.model.auth.ApiKey;
import aussie.core.model.auth.ApiKeyCreateResult;
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
    private static final int MIN_BOOTSTRAP_KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepository repository;
    private final ApiKeyConfig config;

    @Inject
    public ApiKeyService(ApiKeyRepository repository, ApiKeyConfig config) {
        this.repository = repository;
        this.config = config;
    }

    @Override
    public Uni<ApiKeyCreateResult> create(
            String name, String description, Set<String> permissions, Duration ttl, String createdBy) {
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
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        return repository.save(apiKey).replaceWith(new ApiKeyCreateResult(keyId, plaintextKey, apiKey));
    }

    @Override
    public Uni<Optional<ApiKey>> validate(String plaintextKey) {
        if (plaintextKey == null || plaintextKey.isBlank()) {
            return Uni.createFrom().item(Optional.empty());
        }

        String keyHash = hashKey(plaintextKey);
        return repository.findByHash(keyHash).map(opt -> opt.filter(ApiKey::isValid));
    }

    @Override
    public Uni<List<ApiKey>> list() {
        return repository
                .findAll()
                .map(keys -> keys.stream().map(ApiKey::redacted).toList());
    }

    @Override
    public Uni<Boolean> revoke(String keyId) {
        return repository.findById(keyId).flatMap(existingKey -> {
            if (existingKey.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            var revokedKey = existingKey.get().revoke();
            return repository.save(revokedKey).replaceWith(true);
        });
    }

    @Override
    public Uni<Optional<ApiKey>> get(String keyId) {
        return repository.findById(keyId).map(opt -> opt.map(ApiKey::redacted));
    }

    @Override
    public Uni<ApiKeyCreateResult> createWithKey(
            String name,
            String description,
            Set<String> permissions,
            Duration ttl,
            String plaintextKey,
            String createdBy) {
        // Validate the provided key
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new IllegalArgumentException("Plaintext key cannot be null or blank");
        }
        if (plaintextKey.length() < MIN_BOOTSTRAP_KEY_LENGTH) {
            throw new IllegalArgumentException("Key must be at least " + MIN_BOOTSTRAP_KEY_LENGTH + " characters");
        }

        // Validate TTL against configured maximum
        validateTtl(ttl);

        String keyId = generateKeyId();
        String keyHash = hashKey(plaintextKey);

        Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;

        var apiKey = ApiKey.builder(keyId, keyHash)
                .name(name)
                .description(description)
                .permissions(permissions != null ? permissions : Set.of())
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        return repository.save(apiKey).replaceWith(new ApiKeyCreateResult(keyId, plaintextKey, apiKey));
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
