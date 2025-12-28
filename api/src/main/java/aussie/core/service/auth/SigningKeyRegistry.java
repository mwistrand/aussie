package aussie.core.service.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.spi.SigningKeyRepository;

/**
 * Registry for managing signing keys with in-memory caching.
 *
 * <p>
 * This service provides fast, O(1) access to signing keys on the hot path
 * (token issuance and validation). Keys are cached in memory and refreshed
 * periodically from the repository.
 *
 * <h2>Performance</h2>
 * <ul>
 * <li>Get active signing key: ~100ns (volatile field read)</li>
 * <li>Get verification key by ID: ~1μs (HashMap lookup)</li>
 * <li>Cache refresh: Background, non-blocking</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * All public methods are thread-safe. The cache uses volatile fields
 * and concurrent data structures to ensure visibility without locking.
 */
@ApplicationScoped
public class SigningKeyRegistry {

    private static final Logger LOG = Logger.getLogger(SigningKeyRegistry.class);

    private final SigningKeyRepository repository;
    private final KeyRotationConfig config;

    /**
     * Immutable cache state snapshot.
     *
     * <p>Using a single immutable object ensures readers always see
     * a consistent view of all cached data.
     */
    private record CacheState(
            SigningKeyRecord activeKey,
            Map<String, SigningKeyRecord> verificationKeyMap,
            List<SigningKeyRecord> verificationKeys,
            Instant lastRefresh,
            boolean initialized) {

        static final CacheState EMPTY = new CacheState(null, Map.of(), List.of(), null, false);
    }

    // Single volatile reference for atomic cache swaps
    private volatile CacheState cache = CacheState.EMPTY;

    @Inject
    public SigningKeyRegistry(SigningKeyRepository repository, KeyRotationConfig config) {
        this.repository = repository;
        this.config = config;
    }

    /**
     * Initialize the registry by loading keys from the repository.
     *
     * <p>
     * Initialization is asynchronous. Callers should check {@link #isReady()}
     * or use readiness probes before relying on key availability.
     */
    void init(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.info("Key rotation disabled, using static key configuration");
            return;
        }

        LOG.info("Initializing signing key registry...");
        refreshCache()
                .subscribe()
                .with(
                        success -> LOG.info("Signing key registry initialized"),
                        failure -> LOG.error("Failed to initialize signing key registry", failure));
    }

    /**
     * Get the current active signing key.
     *
     * <p>
     * This is the key used for signing new tokens. There should be
     * exactly one ACTIVE key at any time.
     *
     * @return The active signing key
     * @throws IllegalStateException if no active key is configured
     */
    public SigningKeyRecord getCurrentSigningKey() {
        if (!config.enabled()) {
            throw new IllegalStateException("Key rotation not enabled - use static key configuration");
        }

        final var key = cache.activeKey();
        if (key == null) {
            throw new IllegalStateException("No active signing key configured");
        }
        return key;
    }

    /**
     * Get a verification key by its ID.
     *
     * <p>
     * Used during token validation to find the key matching
     * the 'kid' header in the JWT.
     *
     * @param keyId The key identifier
     * @return The key if found and valid for verification
     */
    public Optional<SigningKeyRecord> getVerificationKey(String keyId) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.verificationKeyMap().get(keyId));
    }

    /**
     * Get all keys valid for verification (ACTIVE + DEPRECATED).
     *
     * <p>
     * Used to populate the JWKS endpoint so clients can
     * verify tokens signed with any valid key.
     *
     * @return List of verification keys (may be empty)
     */
    public List<SigningKeyRecord> getVerificationKeys() {
        return cache.verificationKeys();
    }

    /**
     * Check if the registry is initialized and has an active key.
     */
    public boolean isReady() {
        final var state = cache;
        return config.enabled() && state.initialized() && state.activeKey() != null;
    }

    /**
     * Get the timestamp of the last cache refresh.
     */
    public Optional<Instant> getLastRefreshTime() {
        return Optional.ofNullable(cache.lastRefresh());
    }

    /**
     * Register a new signing key with PENDING status.
     *
     * <p>
     * The key will become active after the configured grace period,
     * or can be manually activated via {@link #activateKey(String)}.
     *
     * @param privateKey RSA private key for signing
     * @param publicKey  RSA public key for verification
     * @return Uni with the created key record
     */
    public Uni<SigningKeyRecord> registerKey(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        final var keyId = generateKeyId();
        final var key = SigningKeyRecord.pending(keyId, privateKey, publicKey);

        LOG.infov("Registering new signing key: {0}", keyId);

        return repository
                .store(key)
                .replaceWith(key)
                .invoke(k -> LOG.infov("Key {0} registered with PENDING status", k.keyId()));
    }

    /**
     * Generate a new RSA key pair and register it.
     *
     * @return Uni with the created key record
     */
    public Uni<SigningKeyRecord> generateAndRegisterKey() {
        try {
            final var keyPair = generateKeyPair();
            return registerKey((RSAPrivateKey) keyPair.getPrivate(), (RSAPublicKey) keyPair.getPublic());
        } catch (NoSuchAlgorithmException e) {
            return Uni.createFrom().failure(new IllegalStateException("RSA algorithm not available", e));
        }
    }

    /**
     * Activate a pending key.
     *
     * <p>
     * This transitions the key from PENDING to ACTIVE. If there's
     * already an active key, it will be deprecated.
     *
     * @param keyId The key identifier to activate
     * @return Uni completing when activated
     */
    public Uni<Void> activateKey(String keyId) {
        LOG.infov("Activating key: {0}", keyId);

        return repository
                .findActive()
                .flatMap(currentActive -> {
                    // Deprecate current active key if exists
                    Uni<Void> deprecateOld = currentActive
                            .map(old -> deprecateKey(old.keyId()))
                            .orElse(Uni.createFrom().voidItem());

                    return deprecateOld.flatMap(v -> repository.updateStatus(keyId, KeyStatus.ACTIVE, Instant.now()));
                })
                .invoke(() -> LOG.infov("Key {0} activated", keyId))
                .flatMap(v -> refreshCache());
    }

    /**
     * Deprecate a key (stop signing, continue verifying).
     *
     * @param keyId The key identifier to deprecate
     * @return Uni completing when deprecated
     */
    public Uni<Void> deprecateKey(String keyId) {
        LOG.infov("Deprecating key: {0}", keyId);

        return repository
                .updateStatus(keyId, KeyStatus.DEPRECATED, Instant.now())
                .invoke(() -> LOG.infov("Key {0} deprecated", keyId))
                .flatMap(v -> refreshCache());
    }

    /**
     * Retire a key (stop all usage).
     *
     * @param keyId The key identifier to retire
     * @return Uni completing when retired
     */
    public Uni<Void> retireKey(String keyId) {
        LOG.warnv("Retiring key: {0} - tokens signed with this key will no longer validate", keyId);

        return repository
                .updateStatus(keyId, KeyStatus.RETIRED, Instant.now())
                .invoke(() -> LOG.warnv("Key {0} retired", keyId))
                .flatMap(v -> refreshCache());
    }

    /**
     * Refresh the in-memory cache from the repository.
     *
     * <p>
     * This is called periodically based on the configured interval.
     * If the refresh fails, the cached keys continue to be used.
     */
    @Scheduled(
            every = "${aussie.auth.key-rotation.cache-refresh-interval:5m}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> refreshCache() {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.info("Refreshing signing key cache...");

        return Uni.combine()
                .all()
                .unis(repository.findActive(), repository.findAllForVerification())
                .asTuple()
                .invoke(tuple -> {
                    final var newActiveKey = tuple.getItem1().orElse(null);
                    final var newVerificationKeys = tuple.getItem2();

                    // Build verification key map
                    final var newMap = new HashMap<String, SigningKeyRecord>();
                    for (var key : newVerificationKeys) {
                        newMap.put(key.keyId(), key);
                    }

                    // Atomic swap of entire cache state
                    this.cache = new CacheState(
                            newActiveKey, Map.copyOf(newMap), List.copyOf(newVerificationKeys), Instant.now(), true);

                    LOG.infov(
                            "Cache refreshed: {0} active key, {1} verification keys",
                            newActiveKey != null ? newActiveKey.keyId() : "none", newVerificationKeys.size());
                })
                .replaceWithVoid()
                .onFailure()
                .invoke(e -> LOG.error("Failed to refresh signing key cache", e));
    }

    /**
     * Generate a unique key ID.
     *
     * <p>
     * Format: k-{year}-{quarter}-{short-uuid}
     * Example: k-2024-q1-a1b2c3d4
     */
    private String generateKeyId() {
        final var now = Instant.now();
        final var year = java.time.Year.from(now.atZone(java.time.ZoneOffset.UTC));
        final var month =
                java.time.MonthDay.from(now.atZone(java.time.ZoneOffset.UTC)).getMonthValue();
        final var quarter = (month - 1) / 3 + 1;
        final var shortUuid = UUID.randomUUID().toString().substring(0, 8);
        return String.format("k-%d-q%d-%s", year.getValue(), quarter, shortUuid);
    }

    /**
     * Generate an RSA key pair with the configured key size.
     */
    private KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        final var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(config.keySize());
        return keyGen.generateKeyPair();
    }
}
