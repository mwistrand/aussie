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
 * <p>
 * Handles key generation, hashing, validation, and revocation. Keys are
 * stored as SHA-256 hashes; the plaintext is only returned once at creation.
 */
@ApplicationScoped
public class ApiKeyService implements ApiKeyManagement {

    public static final String API_KEY_NAMESPACE = "aussie_";
    public static final String API_KEY_PREFIX = "aussie_v1_";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int KEY_ID_LENGTH = 8;
    private final SecureRandom secureRandom = new SecureRandom();

    private final ApiKeyRepository repository;
    private final ApiKeyConfig config;

    @Inject
    public ApiKeyService(ApiKeyRepository repository, ApiKeyConfig config) {
        this.repository = repository;
        this.config = config;
    }

    @Override
    public Uni<ApiKeyCreateResult> create(
            String name, String description, String teamId, Set<String> permissions, Duration ttl, String createdBy) {
        // Validate TTL against configured maximum
        validateTtl(ttl);

        String keyId = generateKeyId();
        String plaintextKey = generateSecureKey();
        String keyHash = hashKey(plaintextKey);

        Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;

        var apiKey = ApiKey.builder(keyId, keyHash)
                .name(name)
                .description(description)
                .teamId(teamId)
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
    public Uni<List<ApiKey>> list(int limit, int offset) {
        return repository
                .findPage(limit, offset)
                .map(keys -> keys.stream().map(ApiKey::redacted).toList());
    }

    @Override
    public Uni<Boolean> revoke(String keyId) {
        return repository.findById(keyId).flatMap(existingKey -> {
            if (existingKey.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            return revoke(keyId, existingKey.get().version());
        });
    }

    @Override
    public Uni<Boolean> revoke(String keyId, long expectedVersion) {
        return repository.findById(keyId).flatMap(existingKey -> {
            if (existingKey.isEmpty() || existingKey.get().version() != expectedVersion) {
                return Uni.createFrom().item(false);
            }
            if (existingKey.get().revoked()) {
                return Uni.createFrom().item(true);
            }
            return repository
                    .replaceIfVersion(existingKey.get().revoke(), expectedVersion)
                    .map(result -> result.applied());
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
            String teamId,
            Set<String> permissions,
            Duration ttl,
            String plaintextKey,
            String createdBy) {
        // Validate the provided key
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new IllegalArgumentException("Plaintext key cannot be null or blank");
        }
        if (!isVersionedKey(plaintextKey)) {
            throw new IllegalArgumentException(
                    "Key must use the aussie_v1_ prefix followed by 43 Base64URL characters");
        }

        // Validate TTL against configured maximum
        validateTtl(ttl);

        String keyId = generateKeyId();
        String keyHash = hashKey(plaintextKey);

        Instant expiresAt = ttl != null ? Instant.now().plus(ttl) : null;

        var apiKey = ApiKey.builder(keyId, keyHash)
                .name(name)
                .description(description)
                .teamId(teamId)
                .permissions(permissions != null ? permissions : Set.of())
                .createdBy(createdBy)
                .createdAt(Instant.now())
                .expiresAt(expiresAt)
                .revoked(false)
                .build();

        return repository.save(apiKey).replaceWith(new ApiKeyCreateResult(keyId, plaintextKey, apiKey));
    }

    /**
     * Generate a cryptographically secure random key.
     *
     * @return versioned Base64URL-encoded key string
     */
    private String generateSecureKey() {
        byte[] bytes = new byte[KEY_LENGTH_BYTES];
        secureRandom.nextBytes(bytes);
        return API_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Return whether a credential uses the current strict API-key grammar. */
    public static boolean isVersionedKey(String credential) {
        if (credential == null || credential.length() != API_KEY_PREFIX.length() + 43) {
            return false;
        }
        if (!credential.startsWith(API_KEY_PREFIX)) {
            return false;
        }
        return credential
                .substring(API_KEY_PREFIX.length())
                .chars()
                .allMatch(c -> (c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '-'
                        || c == '_');
    }

    /** Return whether a credential meets the minimum accepted before versioned prefixes. */
    public static boolean isLegacyKey(String credential) {
        return credential != null && credential.length() >= 32;
    }

    /**
     * Generate a short random key ID for display and revocation.
     *
     * @return 8-character hex string
     */
    private String generateKeyId() {
        byte[] bytes = new byte[KEY_ID_LENGTH / 2];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Compute SHA-256 hash of the plaintext key.
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
     * Validate the requested TTL against the configured maximum.
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
     * Format a duration for human-readable display.
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
