package aussie.adapter.out.storage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.cache.CaffeineLocalCache;
import aussie.core.cache.LocalCache;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigCache;
import aussie.core.port.out.TranslationConfigRepository;

/**
 * Tiered storage implementation for translation configurations.
 *
 * <p>Implements a three-tier caching strategy:
 * <ol>
 *   <li><b>L1 (Memory)</b>: Caffeine-based local cache with short TTL (default 5 minutes)</li>
 *   <li><b>L2 (Redis)</b>: Distributed cache for cross-instance consistency (optional)</li>
 *   <li><b>L3 (Cassandra)</b>: Primary persistent storage</li>
 * </ol>
 *
 * <h2>Read Path</h2>
 * <pre>
 * Memory Cache → (miss) → Redis Cache → (miss) → Cassandra → populate caches
 * </pre>
 *
 * <h2>Write Path</h2>
 * <pre>
 * Cassandra → invalidate Redis → invalidate Memory
 * </pre>
 *
 * <p>This implementation follows a write-through invalidation strategy: writes go
 * directly to Cassandra, and caches are invalidated. Subsequent reads will
 * repopulate the caches.
 */
public class TieredTranslationConfigRepository implements TranslationConfigRepository {

    private static final String ACTIVE_CACHE_KEY = "__active__";
    private static final String LIST_CACHE_KEY = "__list__";
    private static final Duration DEFAULT_MEMORY_TTL = Duration.ofMinutes(5);
    private static final long DEFAULT_MEMORY_MAX_SIZE = 100;

    private final TranslationConfigRepository primaryStorage;
    private final TranslationConfigCache distributedCache;
    private final LocalCache<String, TranslationConfigVersion> memoryCache;
    private final LocalCache<String, List<TranslationConfigVersion>> listCache;

    /**
     * Creates a tiered repository with default memory cache settings.
     *
     * @param primaryStorage the primary storage (Cassandra)
     * @param distributedCache the distributed cache (Redis), may be null
     */
    public TieredTranslationConfigRepository(
            TranslationConfigRepository primaryStorage, TranslationConfigCache distributedCache) {
        this(primaryStorage, distributedCache, DEFAULT_MEMORY_TTL, DEFAULT_MEMORY_MAX_SIZE);
    }

    /**
     * Creates a tiered repository with custom memory cache settings.
     *
     * @param primaryStorage the primary storage (Cassandra)
     * @param distributedCache the distributed cache (Redis), may be null
     * @param memoryTtl TTL for memory cache entries
     * @param memoryMaxSize maximum entries in memory cache
     */
    public TieredTranslationConfigRepository(
            TranslationConfigRepository primaryStorage,
            TranslationConfigCache distributedCache,
            Duration memoryTtl,
            long memoryMaxSize) {
        this.primaryStorage = primaryStorage;
        this.distributedCache = distributedCache;
        this.memoryCache = new CaffeineLocalCache<>(memoryTtl, memoryMaxSize);
        this.listCache = new CaffeineLocalCache<>(memoryTtl, 1);
    }

    @Override
    public Uni<Void> save(TranslationConfigVersion version) {
        return primaryStorage
                .save(version)
                .flatMap(v -> invalidateCaches(version.id()))
                .flatMap(v -> invalidateListCaches());
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> getActive() {
        // L1: Check memory cache
        final var memoryCached = memoryCache.get(ACTIVE_CACHE_KEY);
        if (memoryCached.isPresent()) {
            return Uni.createFrom().item(memoryCached);
        }

        // L2: Check distributed cache
        if (distributedCache != null) {
            return distributedCache.getActive().flatMap(redisCached -> {
                if (redisCached.isPresent()) {
                    memoryCache.put(ACTIVE_CACHE_KEY, redisCached.get());
                    return Uni.createFrom().item(redisCached);
                }
                // L3: Fetch from primary storage
                return fetchActiveFromPrimary();
            });
        }

        // L3: Fetch from primary storage (no distributed cache)
        return fetchActiveFromPrimary();
    }

    private Uni<Optional<TranslationConfigVersion>> fetchActiveFromPrimary() {
        return primaryStorage.getActive().flatMap(opt -> {
            if (opt.isPresent()) {
                final var version = opt.get();
                memoryCache.put(ACTIVE_CACHE_KEY, version);
                memoryCache.put(version.id(), version);
                if (distributedCache != null) {
                    return distributedCache.putActive(version).map(v -> opt);
                }
            }
            return Uni.createFrom().item(opt);
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findById(String id) {
        // L1: Check memory cache
        final var memoryCached = memoryCache.get(id);
        if (memoryCached.isPresent()) {
            return Uni.createFrom().item(memoryCached);
        }

        // L2: Check distributed cache
        if (distributedCache != null) {
            return distributedCache.get(id).flatMap(redisCached -> {
                if (redisCached.isPresent()) {
                    memoryCache.put(id, redisCached.get());
                    return Uni.createFrom().item(redisCached);
                }
                // L3: Fetch from primary storage
                return fetchByIdFromPrimary(id);
            });
        }

        // L3: Fetch from primary storage (no distributed cache)
        return fetchByIdFromPrimary(id);
    }

    private Uni<Optional<TranslationConfigVersion>> fetchByIdFromPrimary(String id) {
        return primaryStorage.findById(id).flatMap(opt -> {
            if (opt.isPresent()) {
                final var version = opt.get();
                memoryCache.put(id, version);
                if (distributedCache != null) {
                    return distributedCache.put(version).map(v -> opt);
                }
            }
            return Uni.createFrom().item(opt);
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findByVersion(int versionNumber) {
        // Version number lookups go directly to primary storage
        // We could add caching here if needed, but version number lookups are less common
        return primaryStorage.findByVersion(versionNumber).flatMap(opt -> {
            if (opt.isPresent()) {
                final var version = opt.get();
                memoryCache.put(version.id(), version);
                if (distributedCache != null) {
                    return distributedCache.put(version).map(v -> opt);
                }
            }
            return Uni.createFrom().item(opt);
        });
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions() {
        // L1: Check memory cache
        final var memoryCached = listCache.get(LIST_CACHE_KEY);
        if (memoryCached.isPresent()) {
            return Uni.createFrom().item(memoryCached.get());
        }

        // L2: Check distributed cache
        if (distributedCache != null) {
            return distributedCache.getVersionList().flatMap(redisCached -> {
                if (redisCached.isPresent()) {
                    listCache.put(LIST_CACHE_KEY, redisCached.get());
                    return Uni.createFrom().item(redisCached.get());
                }
                // L3: Fetch from primary storage
                return fetchListFromPrimary();
            });
        }

        // L3: Fetch from primary storage (no distributed cache)
        return fetchListFromPrimary();
    }

    private Uni<List<TranslationConfigVersion>> fetchListFromPrimary() {
        return primaryStorage.listVersions().flatMap(versions -> {
            listCache.put(LIST_CACHE_KEY, versions);
            // Also cache individual versions
            versions.forEach(v -> memoryCache.put(v.id(), v));
            if (distributedCache != null) {
                return distributedCache.putVersionList(versions).map(v -> versions);
            }
            return Uni.createFrom().item(versions);
        });
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions(int limit, int offset) {
        // For paginated queries, we fetch the full list and paginate in memory
        // This works well for small datasets; for large datasets, consider direct DB pagination
        return listVersions().map(all -> all.stream().skip(offset).limit(limit).toList());
    }

    @Override
    public Uni<Integer> getNextVersionNumber() {
        // Version counter always goes to primary storage for consistency
        return primaryStorage.getNextVersionNumber();
    }

    @Override
    public Uni<Boolean> setActive(String versionId) {
        return primaryStorage.setActive(versionId).flatMap(success -> {
            if (success) {
                return invalidateActiveCaches()
                        .flatMap(v -> invalidateListCaches())
                        .map(v -> true);
            }
            return Uni.createFrom().item(false);
        });
    }

    @Override
    public Uni<Boolean> delete(String versionId) {
        return primaryStorage.delete(versionId).flatMap(success -> {
            if (success) {
                return invalidateCaches(versionId)
                        .flatMap(v -> invalidateListCaches())
                        .map(v -> true);
            }
            return Uni.createFrom().item(false);
        });
    }

    private Uni<Void> invalidateCaches(String id) {
        memoryCache.invalidate(id);
        if (distributedCache != null) {
            return distributedCache.invalidate(id);
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> invalidateActiveCaches() {
        memoryCache.invalidate(ACTIVE_CACHE_KEY);
        if (distributedCache != null) {
            return distributedCache.invalidateActive();
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> invalidateListCaches() {
        listCache.invalidate(LIST_CACHE_KEY);
        if (distributedCache != null) {
            return distributedCache.invalidateVersionList();
        }
        return Uni.createFrom().voidItem();
    }

    /**
     * Invalidate all caches. Useful for testing or manual cache clearing.
     */
    public Uni<Void> invalidateAllCaches() {
        memoryCache.invalidateAll();
        listCache.invalidateAll();
        if (distributedCache != null) {
            return distributedCache.invalidateAll();
        }
        return Uni.createFrom().voidItem();
    }
}
