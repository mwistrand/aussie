# Chapter 5: Caching and Thundering Herd Prevention

Caching is not a feature. It is a structural decision that determines how your system behaves under load, how it fails, and how fast it recovers. An API gateway sits at the narrowest point in your request flow -- every millisecond added to the hot path multiplies across every request from every client. Get caching wrong and your gateway either hammers its own storage layer into the ground or serves stale data long enough to cause incidents.

This chapter walks through the caching architecture in Aussie: why each decision was made, what alternatives exist, and where the traps are.

---

## 5.1 Local Cache with Caffeine

The first layer of caching is process-local. Aussie uses Ben Manes' Caffeine library -- the fastest general-purpose Java cache available, backed by a W-TinyLFU admission policy that outperforms pure LRU for most real-world access patterns.

### The Cache Interface

The cache abstraction is intentionally thin. Three files in `api/src/main/java/aussie/core/cache/` define the entire local cache contract:

- `LocalCache.java` -- the generic interface
- `CaffeineLocalCache.java` -- the Caffeine-backed implementation
- `LocalCacheConfig.java` -- Quarkus `@ConfigMapping` for cache configuration

The interface (`LocalCache.java`, lines 17-66) exposes six operations:

```java
// api/src/main/java/aussie/core/cache/LocalCache.java (lines 17-66)
public interface LocalCache<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    void invalidate(K key);
    void invalidateAll();
    Collection<V> values();
    long estimatedSize();
}
```

No async methods. No bulk load. No compute-if-absent. This is deliberate. The local cache is a simple key-value store with TTL. It does not attempt to be a distributed data structure or a loading cache. Complexity in the cache abstraction bleeds into every consumer.

### Per-Entry TTL via the Expiry Interface

Here is where the implementation gets interesting. Caffeine supports three expiration strategies: `expireAfterWrite`, `expireAfterAccess`, and a custom `Expiry` interface that gives you per-entry control. Aussie uses the custom `Expiry` to apply jitter (more on why in Section 5.2).

```java
// api/src/main/java/aussie/core/cache/CaffeineLocalCache.java (lines 55-75)
public CaffeineLocalCache(Duration ttl, long maxSize, double jitterFactor) {
    if (jitterFactor < 0.0 || jitterFactor > 0.5) {
        throw new IllegalArgumentException(
            "Jitter factor must be between 0.0 and 0.5, got: " + jitterFactor);
    }
    this.baseTtlNanos = ttl.toNanos();
    this.jitterFactor = jitterFactor;

    if (jitterFactor == 0.0) {
        // No jitter - use simple expireAfterWrite
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    } else {
        // Use custom expiry with jitter
        this.cache = Caffeine.newBuilder()
                .expireAfter(new JitteredExpiry())
                .maximumSize(maxSize)
                .build();
    }
}
```

The branch at line 62 is worth calling out. When jitter is zero (used in test code -- see `ServiceRegistryTest.java` line 69 where `jitterFactor()` returns `0.0`), the implementation falls back to Caffeine's built-in `expireAfterWrite`. This is not just a simplification; `expireAfterWrite` uses a more efficient internal structure than a custom `Expiry` because Caffeine can batch entries with identical TTLs into timing wheel buckets. There is no reason to pay the cost of per-entry expiry when you have nothing to vary.

The custom `Expiry` implementation (lines 80-102):

```java
// api/src/main/java/aussie/core/cache/CaffeineLocalCache.java (lines 80-102)
private class JitteredExpiry implements Expiry<K, V> {
    @Override
    public long expireAfterCreate(K key, V value, long currentTime) {
        return applyJitter(baseTtlNanos);
    }

    @Override
    public long expireAfterUpdate(K key, V value, long currentTime,
                                  long currentDuration) {
        return applyJitter(baseTtlNanos);
    }

    @Override
    public long expireAfterRead(K key, V value, long currentTime,
                                long currentDuration) {
        return currentDuration; // Don't change TTL on read
    }

    private long applyJitter(long baseTtl) {
        // Apply jitter: multiply by random value in [1-jitterFactor, 1+jitterFactor]
        final var jitter = ThreadLocalRandom.current().nextDouble() * 2 * jitterFactor;
        final var jitterMultiplier = 1.0 - jitterFactor + jitter;
        return (long) (baseTtl * jitterMultiplier);
    }
}
```

Three things to note:

1. **`expireAfterRead` returns `currentDuration` unchanged.** Reads do not extend the TTL. This is expire-after-write semantics with jitter, not expire-after-access. If you used expire-after-access, a popular cache entry would never expire, and you would never pick up changes from storage.

2. **`expireAfterUpdate` re-jitters.** When a value is overwritten (e.g., after a cache refresh), it gets a fresh jittered TTL. This prevents a scenario where all entries that were refreshed in the same batch end up with correlated expiration times.

3. **`ThreadLocalRandom`**, not `Math.random()` or a shared `Random`. `ThreadLocalRandom` is lock-free and does not contend across threads. In a hot cache path, this matters.

### Configuration

```java
// api/src/main/java/aussie/core/cache/LocalCacheConfig.java (lines 34-99)
@ConfigMapping(prefix = "aussie.cache.local")
public interface LocalCacheConfig {
    @WithDefault("PT30S")
    Duration serviceRoutesTtl();        // How long compiled routes stay cached

    @WithDefault("PT30S")
    Duration rateLimitConfigTtl();      // How long rate limit configs stay cached

    @WithDefault("PT30S")
    Duration samplingConfigTtl();       // How long sampling configs stay cached

    @WithDefault("10000")
    long maxEntries();                  // LRU eviction bound

    @WithDefault("0.1")
    double jitterFactor();              // +/-10% TTL variation
}
```

The defaults tell a story: 30 seconds of staleness is acceptable for routing and rate limit configuration. This means that after an admin registers a new service, it may take up to ~33 seconds (30s + 10% jitter) for all gateway instances to pick it up. That trade-off is explicit and configurable.

The `maxEntries` default of 10,000 with LRU eviction via `maximumSize` ensures bounded memory. Caffeine's W-TinyLFU is more sophisticated than pure LRU -- it uses a frequency sketch to protect frequently-accessed entries from being evicted by a scan of new entries -- but the configuration knob is `maximumSize`, and the mental model of "keep the most-used entries, evict the rest" is close enough.

### What a Senior Engineer Might Do Instead

A common approach is to use Quarkus' built-in `@CacheResult` annotation from the `quarkus-cache` extension. This gives you Caffeine under the hood with less boilerplate. The problem is control. `@CacheResult` does not support per-entry TTL jitter, and it ties your cache lifecycle to method invocations rather than explicit cache management. When you need to invalidate on write-through or coordinate refresh across callers, annotation-driven caching becomes a liability.

Another approach is Guava's `CacheBuilder`. Caffeine is its direct successor, with better performance characteristics and active maintenance. There is no reason to use Guava's cache in new code.

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| Custom `Expiry` over `expireAfterWrite` | Per-entry jitter prevents stampedes | Slightly more complex code; Caffeine uses less efficient internal path |
| `maximumSize(10000)` | Bounded memory | Could evict entries for high-cardinality deployments |
| `Optional` return from `get()` | Null-safe API | Slight allocation overhead per call |
| TTL-based expiry, not event-driven invalidation | Simple; no cross-instance messaging needed | Up to TTL seconds of staleness |

---

## 5.2 Why Jitter Matters

Jitter is the single most important detail in a distributed cache. Without it, your caching layer becomes a synchronized load amplifier.

### The Problem: Correlated Expiration

Consider a gateway cluster with 20 instances. At time T=0, all 20 instances start up and cache the service route table with a 30-second TTL. At T=30, all 20 caches expire simultaneously. All 20 instances hit the database to refresh. The database sees a 20x spike in load for a query that normally trickles in at one request every few seconds.

This is the thundering herd at the cache layer.

It gets worse with popular entries. If an entry is accessed frequently, every instance caches it early in the TTL window, and they all expire together. The most popular entries -- the ones that matter most -- are the ones most likely to cause a stampede.

### The Math

With a base TTL of 30 seconds and a jitter factor of 0.1 (the default in `LocalCacheConfig.java` line 97), the `applyJitter` method at line 96 of `CaffeineLocalCache.java` computes:

```
jitter = random(0.0, 1.0) * 2 * 0.1 = random(0.0, 0.2)
multiplier = 1.0 - 0.1 + jitter = 0.9 + random(0.0, 0.2)
            = random(0.9, 1.1)
actual_ttl = 30s * random(0.9, 1.1)
           = random(27s, 33s)
```

The TTL for any given entry lands uniformly in the range [27s, 33s] -- a 6-second window. For 20 instances, if each caches the same entry at approximately the same time, their expirations spread uniformly across 6 seconds. Instead of 20 simultaneous requests to the database, you get roughly 3-4 requests per second over that window.

### Comparison: No Jitter vs. Global Jitter vs. Per-Entry Jitter

**No Jitter** -- All entries expire at exactly the base TTL.

```
Instance 1:  |====== 30s ======|REFRESH|====== 30s ======|REFRESH|
Instance 2:  |====== 30s ======|REFRESH|====== 30s ======|REFRESH|
Instance 3:  |====== 30s ======|REFRESH|====== 30s ======|REFRESH|
Instance 4:  |====== 30s ======|REFRESH|====== 30s ======|REFRESH|
                                   ^                         ^
                                   |                         |
                            ALL REFRESH HERE          ALL REFRESH HERE
                            (thundering herd)         (thundering herd)

Timeline:    |----|----|----|----|----|----|----|----|----|----|
             0s   6s  12s  18s  24s  30s  36s  42s  48s  54s  60s
DB Load:                              ^^^^                    ^^^^
                                      SPIKE                   SPIKE
```

Every refresh cycle is a coordinated spike. The database load profile looks like a series of sharp impulses.

**Global Jitter (per-instance offset)** -- Each instance adds a fixed random offset at startup.

```
Instance 1:  |==== 28s ====|REFRESH|==== 28s ====|REFRESH|
Instance 2:  |====== 30s ======|REFRESH|====== 30s ======|REFRESH|
Instance 3:  |======= 31s =======|REFRESH|======= 31s =======|REFRESH|
Instance 4:  |======== 33s ========|REFRESH|======== 33s ========|REFRESH|

Timeline:    |----|----|----|----|----|----|----|----|----|----|
             0s   6s  12s  18s  24s  30s  36s  42s  48s  54s  60s
DB Load:                         ~    ~ ~   ~        ~   ~~    ~
                                 spread out           spread out
```

Better. Expirations are staggered. But the offset is fixed per instance, so if two instances happen to get similar offsets, they remain correlated forever. Also, this approach only works when all instances start at about the same time. If instances restart at different times, their offsets are already naturally staggered and the global jitter adds nothing.

**Per-Entry Jitter (what Aussie does)** -- Each cache entry gets an independent random TTL.

```
Instance 1:  |=== 27.3s ===|R|=== 32.1s ====|R|=== 28.5s ===|R|
Instance 2:  |==== 29.8s ====|R|== 27.0s ==|R|==== 31.2s ====|R|
Instance 3:  |===== 31.5s =====|R|=== 29.4s ===|R|=== 28.9s ===|R|
Instance 4:  |==== 30.2s ====|R|==== 32.8s =====|R|== 27.1s ==|R|

Timeline:    |----|----|----|----|----|----|----|----|----|----|
             0s   6s  12s  18s  24s  30s  36s  42s  48s  54s  60s
DB Load:                         ~  ~ ~  ~ ~ ~  ~ ~  ~ ~ ~  ~
                                 uniformly distributed
```

Expirations are independently distributed. No correlation between instances, no correlation between refresh cycles. Even if all instances start at the same instant, each entry's TTL is independently randomized, and after the first refresh, the re-jitter on update (`expireAfterUpdate` returning a fresh jittered value) ensures that correlations do not re-emerge over time.

### Why +/-10% and Not More?

The jitter factor is bounded to [0.0, 0.5] by the validation at line 56 of `CaffeineLocalCache.java`. The default of 0.1 is conservative. A 10% jitter on a 30-second TTL gives a 6-second spread window, which is sufficient for most deployments (20-50 instances).

If you increased jitter to 0.3, you would get an 18-second window, but your effective minimum TTL drops to 21 seconds. That means some entries expire 30% sooner than expected, which may not meet your staleness SLO. Jitter is a trade-off between stampede prevention and consistency guarantees. The default balances these well for most cases.

For very large clusters (hundreds of instances), you might increase jitter to 0.2 or use a different strategy entirely -- such as staggered background refresh (see Section 5.6).

---

## 5.3 Request Coalescing in ServiceRegistry

Jitter spreads expirations across time. Request coalescing handles the case where multiple requests hit the same instance during the brief window when its cache has expired. Without coalescing, 100 concurrent requests arriving at a gateway instance with an expired cache would each trigger an independent refresh -- 100 database queries for the same data.

The `ServiceRegistry` implements request coalescing with an elegant pattern combining `AtomicReference`, Mutiny's `Uni`, `compareAndSet`, and `memoize().indefinitely()`.

### The Full Implementation

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 65-68)
// TTL tracking for multi-instance cache refresh
private final AtomicReference<Instant> lastRefreshed =
    new AtomicReference<>(Instant.MIN);

// Coalesces concurrent refresh requests to prevent thundering herd
private final AtomicReference<Uni<Void>> inFlightRefresh = new AtomicReference<>();
```

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 120-123)
private boolean isCacheStale() {
    final var lastRefresh = lastRefreshed.get();
    return lastRefresh.plus(routeCacheTtl).isBefore(Instant.now());
}
```

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 138-169)
private Uni<Void> ensureCacheFresh() {
    if (!isCacheStale()) {
        return Uni.createFrom().voidItem();
    }

    return Uni.createFrom().deferred(() -> {
        // Re-check after entering deferred block; another thread may have refreshed
        if (!isCacheStale()) {
            return Uni.createFrom().voidItem();
        }

        // Join an in-flight refresh if one exists
        var existing = inFlightRefresh.get();
        if (existing != null) {
            return existing;
        }

        // Create new refresh that clears itself on completion
        var refresh = refreshRouteCache()
                .onTermination()
                .invoke(() -> inFlightRefresh.set(null))
                .memoize()
                .indefinitely();

        // Try to set as the in-flight refresh; if another thread won, use theirs
        if (inFlightRefresh.compareAndSet(null, refresh)) {
            return refresh;
        }
        var winner = inFlightRefresh.get();
        return winner != null ? winner : refresh;
    });
}
```

### Step-by-Step Breakdown

**Step 1: Fast path (line 139).** If the cache is not stale, return immediately. No allocation, no deferred block. This is the common case -- the vast majority of requests hit a fresh cache.

**Step 2: `Uni.createFrom().deferred()` (line 143).** The `deferred` block delays evaluation until subscription. This is critical in reactive programming: without `deferred`, the body would execute eagerly when `ensureCacheFresh()` is called, not when the returned `Uni` is subscribed to. Since Mutiny is lazy, you want the staleness check and CAS logic to happen at subscription time, not at assembly time.

**Step 3: Double-checked staleness (lines 145-147).** Between the outer check at line 139 and entering the deferred block, another thread may have completed a refresh. Check again. This is the reactive equivalent of double-checked locking.

**Step 4: Join existing in-flight refresh (lines 150-152).** If `inFlightRefresh` already holds a `Uni`, another thread is already refreshing. Return that same `Uni`. Because of `memoize().indefinitely()` (explained below), subscribing to it a second time does not trigger a second refresh -- it shares the result.

**Step 5: Create the refresh Uni (lines 155-160).**

```java
var refresh = refreshRouteCache()
        .onTermination()
        .invoke(() -> inFlightRefresh.set(null))
        .memoize()
        .indefinitely();
```

This chain does four things:

- `refreshRouteCache()` creates a `Uni<Void>` that fetches all registrations from storage and rebuilds the compiled route cache.
- `.onTermination().invoke(...)` clears the `inFlightRefresh` reference when the refresh completes (success or failure). This ensures subsequent requests create a new refresh rather than subscribing to a completed one.
- `.memoize().indefinitely()` is the key. A normal `Uni` re-executes its pipeline on every subscription. `memoize().indefinitely()` caches the result of the first subscription and replays it to all subsequent subscribers. This is what makes coalescing work: 100 concurrent subscribers all get the same result from a single database query.

**Step 6: CAS to install (lines 163-167).** `compareAndSet(null, refresh)` atomically installs the new refresh Uni only if no other thread installed one first. If CAS succeeds, this thread's refresh is the one everyone will share. If CAS fails, another thread won the race; grab their refresh and return it. The null-check fallback at line 167 handles the edge case where the winning thread's refresh has already completed and cleared the reference.

### Sequence Diagram

```
Request A          Request B          Request C          Database
    |                  |                  |                  |
    |--isCacheStale?-->|                  |                  |
    |  yes             |                  |                  |
    |--deferred------->|                  |                  |
    |  re-check: stale |                  |                  |
    |  existing: null   |                  |                  |
    |  create refresh   |                  |                  |
    |  CAS(null->Uni): ok                 |                  |
    |--refreshRouteCache()--------------->|----------------->|
    |                  |--isCacheStale?-->|                  |
    |                  |  yes             |                  |
    |                  |--deferred------->|                  |
    |                  |  re-check: stale |                  |
    |                  |  existing: Uni   |                  |
    |                  |  return existing |                  |
    |                  |                  |--isCacheStale?-->|
    |                  |                  |  yes             |
    |                  |                  |--deferred------->|
    |                  |                  |  re-check: stale |
    |                  |                  |  existing: Uni   |
    |                  |                  |  return existing |
    |                  |                  |                  |
    |<-----------------result from DB----|<-----------------|
    |  (memoized)      |                  |                  |
    |  onTermination: clear ref           |                  |
    |                  |<--memoized result|                  |
    |                  |                  |<--memoized result|
```

One database query. Three requests served.

### The Consumer: findRouteAsync

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 434-436)
public Uni<Optional<RouteLookupResult>> findRouteAsync(String path, String method) {
    return ensureCacheFresh().map(v -> findRouteInCache(path, method));
}
```

The freshness check is chained with `.map()` so the route lookup only happens after the cache is confirmed fresh. If the cache was already fresh, `ensureCacheFresh()` returns `Uni.createFrom().voidItem()` and the map executes immediately. If a refresh was needed, the map executes after the single shared refresh completes.

### What a Senior Engineer Might Do Instead

A common alternative is a `synchronized` block or a `ReentrantLock` around the refresh. This works but blocks threads. In a reactive system on Vert.x's event loop, blocking a thread is a cardinal sin -- it pins one of a small number of event loop threads and reduces throughput for the entire instance.

Another approach is to use Caffeine's built-in `AsyncLoadingCache`, which provides automatic refresh with coalescing. The reason Aussie does not use it here is that the route cache is not a simple key-value lookup -- it is a bulk-loaded, compiled routing table that is refreshed as a unit. An `AsyncLoadingCache` is designed for per-key loading, not for "reload everything at once."

A third approach, used in some systems, is to schedule background refresh on a timer. This avoids the request-driven refresh entirely but introduces a fixed refresh cadence that wastes resources when traffic is low and may still be too slow when traffic is high. The on-demand pattern used here is more efficient: it only refreshes when someone actually needs the data.

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| `AtomicReference` + CAS over `synchronized` | Non-blocking; safe on event loop | More complex code; possible spurious Uni creation |
| `memoize().indefinitely()` | Single execution shared across all subscribers | Requires manual cleanup via `onTermination` |
| `deferred()` wrapper | Correct lazy evaluation in reactive pipeline | Extra allocation per stale-cache request |
| `Instant.MIN` initial value | Forces first call to always refresh | None significant |

---

## 5.4 JWKS Cache Coalescing

The JWKS cache (`JwksCacheService.java`) solves a similar problem -- preventing thundering herd on cache miss -- but uses a different coalescing pattern. The difference is structural: the service registry has one global cache that is refreshed as a whole, while the JWKS cache has many independent entries keyed by URI, each of which can expire independently.

### The Pattern: ConcurrentHashMap.computeIfAbsent

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java (lines 51)
private final Map<URI, Uni<JsonWebKeySet>> inFlightFetches = new ConcurrentHashMap<>();
```

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java (lines 74-81)
@Override
public Uni<JsonWebKeySet> getKeySet(URI jwksUri) {
    var cached = cache.getIfPresent(jwksUri);
    if (cached != null && !cached.isExpired()) {
        LOG.debugv("Using cached JWKS for {0}", jwksUri);
        return Uni.createFrom().item(cached.keySet());
    }
    return getOrCreateFetch(jwksUri);
}
```

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java (lines 87-101)
private Uni<JsonWebKeySet> getOrCreateFetch(URI jwksUri) {
    return Uni.createFrom().deferred(() -> {
        // Use computeIfAbsent to ensure only one fetch per URI
        var fetch = inFlightFetches.computeIfAbsent(jwksUri, this::createFetch);
        return fetch;
    });
}

private Uni<JsonWebKeySet> createFetch(URI jwksUri) {
    return fetchAndCache(jwksUri)
            .onTermination()
            .invoke(() -> inFlightFetches.remove(jwksUri))
            .memoize()
            .indefinitely();
}
```

### Why This Pattern Differs from ServiceRegistry

The `ServiceRegistry` uses `AtomicReference<Uni<Void>>` with manual CAS because there is a single global refresh operation. There is one slot, and threads compete for it.

The `JwksCacheService` uses `ConcurrentHashMap<URI, Uni<JsonWebKeySet>>` because there are many independent coalescing groups -- one per JWKS URI. Each identity provider has its own JWKS endpoint, and fetches to different endpoints should not block each other. `ConcurrentHashMap.computeIfAbsent` provides atomic put-if-absent semantics per key, which is exactly what per-URI coalescing needs.

The shared elements of the pattern are:

1. **`Uni.createFrom().deferred()`** for lazy evaluation at subscription time.
2. **`.memoize().indefinitely()`** to share a single execution across all concurrent subscribers.
3. **`.onTermination().invoke()`** to clean up the in-flight reference so future requests create a new fetch.

### Stale Fallback

The JWKS cache adds a detail the service registry does not: stale fallback on failure.

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java (lines 143-153)
.onFailure()
.recoverWithUni(error -> {
    LOG.errorv(error, "Failed to fetch JWKS from {0}", jwksUri);
    // Try to use stale cached keys if available
    var stale = cache.getIfPresent(jwksUri);
    if (stale != null) {
        LOG.warnv("Using stale cached JWKS for {0} due to: {1}",
                  jwksUri, error.getMessage());
        return Uni.createFrom().item(stale.keySet());
    }
    return Uni.createFrom().failure(error);
});
```

If an identity provider's JWKS endpoint is down, the cache returns the last known good key set even after TTL expiry. This is the right trade-off for JWKS: keys rotate slowly (typically days or weeks), so stale data is almost always correct. Failing authentication for all users because an IdP endpoint returned a 500 is far worse than using a key set that is a few minutes old.

The service registry does not do stale fallback because routing data is more volatile (services are registered and unregistered frequently) and the consequences of stale routing (sending traffic to a deregistered service) are different from the consequences of stale keys (authentication works, just with slightly old keys).

### Force Refresh and Invalidation

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java (lines 109-121)
@Override
public Uni<JsonWebKeySet> refresh(URI jwksUri) {
    LOG.infov("Force refreshing JWKS for {0}", jwksUri);
    cache.invalidate(jwksUri);
    inFlightFetches.remove(jwksUri); // Clear any stale in-flight fetch
    return getOrCreateFetch(jwksUri);
}

@Override
public void invalidate(URI jwksUri) {
    LOG.infov("Invalidating cached JWKS for {0}", jwksUri);
    cache.invalidate(jwksUri);
    inFlightFetches.remove(jwksUri);
}
```

The `refresh` method clears both the Caffeine cache and any in-flight fetch before creating a new one. This is necessary for key rotation: if a JWT fails verification because the signing key rotated, the caller can force a refresh to pick up the new key set. Clearing `inFlightFetches` ensures the new fetch actually goes to the network rather than returning a stale memoized result from a concurrent in-flight fetch.

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| `ConcurrentHashMap` over `AtomicReference` | Per-URI coalescing; independent fetches per IdP | More memory (one map entry per active fetch) |
| `computeIfAbsent` over manual CAS | Simpler, fewer race condition edge cases | Holds CHM segment lock during `createFetch` (but `createFetch` is cheap -- it just assembles the Uni pipeline, it does not block on I/O) |
| Stale fallback on failure | Authentication continues working during IdP outages | Could serve revoked keys if rotation coincides with outage |
| Caffeine with `recordStats()` + Micrometer integration | Observable cache hit rates in production | Minor overhead from stats recording |

---

## 5.5 Multi-Tier Cache Hierarchy

Aussie's caching is not a single layer. It is a hierarchy where each tier protects the next from load:

```
Request --> [Local Caffeine Cache] --> [Redis Distributed Cache] --> [Cassandra]
              (per-instance)            (shared across instances)     (source of truth)
```

### Tier 1: Local Caffeine Cache (Process Memory)

- **Latency:** Sub-microsecond (direct hash lookup)
- **Capacity:** Bounded by `maxEntries` (default: 10,000)
- **Scope:** Per-instance. No sharing between gateway instances.
- **TTL:** 30 seconds (configurable via `LocalCacheConfig`)
- **Consistency:** Eventual. Stale for up to TTL + jitter.

This tier exists to avoid any I/O at all for repeated requests. The route cache in `ServiceRegistry` is a local `ConcurrentHashMap<String, CompiledRoute>` (line 62) that is refreshed in bulk when the TTL expires. The JWKS cache uses a Caffeine cache with `maximumSize` and `expireAfterWrite`.

### Tier 2: Redis Distributed Cache

- **Latency:** ~1ms (network round-trip within datacenter)
- **Capacity:** Limited by Redis memory
- **Scope:** Shared across all gateway instances in a deployment
- **TTL:** 15 minutes (default from `RedisCacheProvider.java` line 36)
- **Consistency:** Eventual. But changes are visible to all instances immediately after write.

The Redis layer (`RedisConfigurationCache.java`) stores serialized `ServiceRegistration` objects under the key prefix `aussie:config:`. When `ServiceRegistry.getService()` is called (line 360), it checks Redis first via the `ConfigurationCache` port, then falls back to the repository:

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 358-367)
public Uni<Optional<ServiceRegistration>> getService(String serviceId) {
    // Try cache first, then fall back to repository
    return cache.get(serviceId).flatMap(cached -> {
        if (cached.isPresent()) {
            return Uni.createFrom().item(cached);
        }
        return repository.findById(serviceId).call(opt -> opt.map(cache::put)
                .orElse(Uni.createFrom().voidItem()));
    });
}
```

This is the cache-aside pattern: check cache, miss, load from source, populate cache. The Redis `put` uses `SETEX` with TTL (line 62 of `RedisConfigurationCache.java`), so entries expire automatically.

### Tier 3: Cassandra (Source of Truth)

- **Latency:** 2-10ms (depending on consistency level and cluster topology)
- **Capacity:** Effectively unlimited
- **Scope:** Global. The authoritative store for all service registrations.
- **Consistency:** Tunable per query. Write path uses quorum for durability.

Cassandra is only hit when both local and Redis caches miss, or during bulk refresh of the compiled route table. The hierarchy ensures that Cassandra's query volume is a tiny fraction of gateway request volume.

### How the Tiers Interact on Write

When a service is registered (line 269-274 of `ServiceRegistry.java`):

```java
return repository
        .save(service)                        // Write to Cassandra
        .invoke(() -> compileAndCacheRoutes(service))  // Update local compiled routes
        .call(() -> cache.put(service))        // Write-through to Redis
        .map(v -> RegistrationResult.success(service));
```

This is write-through on the instance that handles the registration. Other instances will pick up the change when their local cache TTL expires and they refresh from Cassandra (or from Redis, if they do a per-key lookup via `getService()`).

### The No-Op Cache

When Redis is not configured, the `ConfigurationCache` port is satisfied by `NoOpConfigurationCache`:

```java
// api/src/main/java/aussie/adapter/out/storage/NoOpConfigurationCache.java (lines 18-48)
public class NoOpConfigurationCache implements ConfigurationCache {
    public static final NoOpConfigurationCache INSTANCE = new NoOpConfigurationCache();

    @Override
    public Uni<Optional<ServiceRegistration>> get(String serviceId) {
        return Uni.createFrom().item(Optional.empty());  // Always "miss"
    }

    @Override
    public Uni<Void> put(ServiceRegistration service) {
        return Uni.createFrom().voidItem();  // Discard
    }
    // ...
}
```

This collapses the hierarchy to Local -> Cassandra without any code changes. The same code paths work with or without Redis. This is why the cache is a port interface, not a concrete dependency.

### What a Senior Engineer Might Do Instead

A common approach is to use Redis as the only cache (no local tier). This is simpler but adds ~1ms of network latency to every cache hit. At gateway scale -- tens of thousands of requests per second -- that 1ms adds up. The local tier eliminates network I/O entirely for the hot path.

Another approach is to use pub/sub or event-driven invalidation instead of TTL. Redis Pub/Sub or Cassandra CDC could push change notifications to all instances, invalidating their local caches immediately. This gives you near-instant consistency but adds operational complexity (what happens when a pub/sub message is lost? what about during network partitions?) and couples instance availability to the messaging layer. TTL-based consistency is simpler and self-healing: even if something goes wrong, the cache will eventually refresh itself.

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| Three tiers over one | Each tier absorbs load for the next | More moving parts; more failure modes |
| TTL-based consistency over pub/sub | Self-healing; no messaging infrastructure | Up to TTL seconds of staleness |
| Redis as optional (No-Op fallback) | Works in simple deployments without Redis | Two-tier hierarchy has higher Cassandra load |
| Write-through on mutation | Immediate Redis consistency on the writing instance | Does not help other instances until their TTL expires |

---

## 5.6 Cache-Aside with Async Refresh

The caching patterns in Aussie combine two strategies: **cache-aside** for per-key lookups and **async bulk refresh** for the compiled route table.

### Cache-Aside for Individual Entries

The `getService()` method in `ServiceRegistry` (lines 358-367) implements classic cache-aside:

1. Check cache (Redis).
2. On miss, load from source (Cassandra).
3. Populate cache with the loaded value.
4. Return the value.

In a reactive context, this translates to a `flatMap` chain:

```java
return cache.get(serviceId).flatMap(cached -> {
    if (cached.isPresent()) {
        return Uni.createFrom().item(cached);
    }
    return repository.findById(serviceId).call(opt -> opt.map(cache::put)
            .orElse(Uni.createFrom().voidItem()));
});
```

The `.call()` operator is important here. Unlike `.invoke()` which runs synchronous side effects, `.call()` chains an asynchronous operation (the Redis `put`) into the pipeline and waits for it to complete before proceeding. The cache write happens asynchronously but is completed before the response is returned, ensuring the next request for the same key will hit the cache.

### Async Bulk Refresh for the Route Table

The `ensureCacheFresh()` + `refreshRouteCache()` pattern is different. It does not check the cache per key. It reloads the entire route table from Cassandra in bulk:

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java (lines 102-113)
private Uni<Void> refreshRouteCache() {
    return repository
            .findAll()
            .invoke(registrations -> {
                compiledRoutes.clear();
                for (ServiceRegistration registration : registrations) {
                    compileAndCacheRoutes(registration);
                }
                lastRefreshed.set(Instant.now());
            })
            .replaceWithVoid();
}
```

This is not cache-aside. It is a periodic full reload triggered by staleness detection. The reason is performance: route matching requires compiled patterns in a `ConcurrentHashMap`, and loading them one at a time on cache miss would mean the first request after a cold start (or after TTL expiry) would take the hit of loading just one route, while subsequent requests would gradually warm the cache. A bulk reload ensures the entire route table is available after a single database query.

### The Reactive Context Matters

In a traditional blocking application, cache-aside with async refresh might use a `ScheduledExecutorService` for background refresh and `synchronized` blocks for mutual exclusion. In Aussie's reactive context:

- **No blocking.** Everything returns `Uni` or `Multi`. The event loop is never blocked waiting for a cache operation.
- **No scheduled timers for refresh.** Refresh is demand-driven: it happens when a request discovers the cache is stale. This is more efficient than polling on a fixed schedule because it naturally adapts to traffic patterns.
- **Coalescing is subscription-based.** The `memoize().indefinitely()` pattern leverages Mutiny's reactive semantics to share a single execution across concurrent subscribers without any explicit thread coordination primitives like locks or semaphores.

### What a Senior Engineer Might Do Instead

A common pattern is "refresh-behind" (also called "stale-while-revalidate"): serve the stale cached value immediately while triggering an async refresh in the background. This eliminates the latency spike on the first request after TTL expiry but requires more careful reasoning about consistency. Aussie's current approach blocks the requesting thread (reactively, via the `Uni` chain) until the refresh completes, which means the first request after TTL expiry pays the full refresh cost. For a 30-second TTL and a Cassandra `findAll` that completes in single-digit milliseconds, this is acceptable. For longer-running refreshes, stale-while-revalidate would be worth the added complexity.

Another approach is to use Caffeine's `refreshAfterWrite` with an `AsyncCacheLoader`. This triggers background refresh after a configurable duration while continuing to serve the stale entry. It is essentially stale-while-revalidate built into the cache. The reason Aussie does not use it for the route table is, again, that the route table is a bulk-loaded structure, not a per-key cache.

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| Cache-aside (per-key) for `getService` | Simple; standard pattern; lazy loading | First request after miss pays full latency |
| Bulk refresh for route table | All routes available after one query | Stale for up to TTL seconds; refresh reloads everything even if only one route changed |
| Demand-driven refresh over scheduled | Adapts to traffic; no wasted work at low traffic | First request after TTL pays refresh cost |
| Blocking on refresh (not stale-while-revalidate) | Simple consistency model; no stale reads during refresh | Latency spike on first request after TTL expiry |

---

## Summary

The caching architecture in Aussie is built on three reinforcing ideas:

1. **Jitter prevents correlated failures.** Per-entry TTL jitter via Caffeine's `Expiry` interface ensures that cache expirations do not synchronize across instances or across refresh cycles. This is a small amount of code with an outsized impact on system stability.

2. **Request coalescing prevents amplification.** The `AtomicReference<Uni<Void>>` + `compareAndSet` + `memoize().indefinitely()` pattern in `ServiceRegistry` and the `ConcurrentHashMap.computeIfAbsent` pattern in `JwksCacheService` both ensure that concurrent cache misses result in a single backend request, not N. The two patterns differ because they solve different cardinality problems (one global refresh vs. per-key deduplication), but they share the same underlying mechanism: reactive memoization with cleanup.

3. **Multi-tier caching provides defense in depth.** Local (Caffeine) absorbs the vast majority of reads. Redis provides cross-instance sharing and reduces Cassandra load. Cassandra is the durable source of truth that is only hit on cold starts, TTL expiry, or cache misses that propagate through both tiers. Each tier is optional -- the system degrades gracefully when a tier is unavailable.

None of these ideas are novel. They are well-established patterns from distributed systems literature. The value is in the specific combination and the attention to detail in the implementation: using `ThreadLocalRandom` instead of `Math.random()`, using `deferred()` for correct lazy evaluation, using `onTermination` for cleanup, bounding jitter to prevent unacceptable staleness, and providing a `NoOpConfigurationCache` so the system works without Redis.

The biggest risk in this architecture is the 30-second staleness window. For most API gateway use cases, this is fine. If your use case requires sub-second propagation of configuration changes, you will need to add a pub/sub invalidation layer on top of this foundation -- but you should still keep the TTL-based refresh as a fallback, because pub/sub messages can be lost.
