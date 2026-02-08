# Chapter 3: Token Revocation -- Probabilistic Data Structures at Scale

---

## The Fundamental Problem

JWTs are designed to be stateless. The entire authorization decision -- who you are, what you can do, when the token expires -- is encoded into the token itself and signed by the issuer. The relying party (in this case, the Aussie API Gateway) verifies the signature, checks the expiry, and moves on. No database lookup. No session store. This is what makes JWTs fast and scalable.

Revocation breaks the model completely.

The moment you need to invalidate a specific JWT before it expires -- a compromised credential, a user clicking "log out everywhere," an administrator revoking access -- you need state. You need every request to check "has this token been revoked?" before granting access. And you need it on every single request, at gateway-level latency (microseconds, not milliseconds), across every instance in a multi-node deployment.

This is harder than it sounds for three reasons:

**1. The asymmetry of revocation volume.** In a system handling 10,000 requests per second, revocations might happen a few hundred times per day. That means 99.99%+ of revocation checks will return "not revoked." If you are paying 1-5ms of network latency for a Redis lookup on every request, you are spending the vast majority of that I/O budget confirming what you already knew: the token is fine.

**2. The consistency requirement is one-directional.** A false positive (saying a valid token is revoked) is annoying but safe -- the user re-authenticates. A false negative (saying a revoked token is valid) is a security breach. This asymmetry matters: it means you cannot cache "not revoked" results aggressively, but you can tolerate probabilistic data structures that sometimes say "maybe revoked."

**3. Multi-instance coordination is non-trivial.** When instance A revokes a token, instance B needs to know about it before it processes the next request carrying that token. The obvious approach (shared database) works but reintroduces the latency problem. Pub/sub introduces eventual consistency, which means there is a window where a revoked token is still accepted.

The solution in Aussie is a tiered caching hierarchy that eliminates remote lookups for the overwhelming majority of requests, uses a probabilistic data structure (bloom filter) for the fast path, and falls back to an authoritative remote store only when necessary.

---

## Tiered Caching Architecture

The revocation check in `TokenRevocationService` implements a four-tier hierarchy. Each tier is cheaper and faster than the next, and the system short-circuits as early as possible.

```
Request
   |
   v
+-----------------------------------------------------------+
| Tier 0: TTL Shortcut                                      |
| Skip check for tokens expiring within threshold (30s)     |
+---------------------------+-------------------------------+
                            | Token has sufficient TTL
                            v
+-----------------------------------------------------------+
| Tier 1: Bloom Filter (~100ns)                             |
| "Definitely not revoked" check - no network I/O           |
+---------------------------+-------------------------------+
                            | Maybe revoked (positive)
                            v
+-----------------------------------------------------------+
| Tier 2: Local Cache (~1us)                                |
| LRU cache for confirmed revocations                       |
+---------------------------+-------------------------------+
                            | Not in cache
                            v
+-----------------------------------------------------------+
| Tier 3: Remote Store (~1-5ms)                             |
| Redis/SPI backend - authoritative source                  |
+-----------------------------------------------------------+
```

The implementation lives in `TokenRevocationService.isRevoked()` (`api/src/main/java/aussie/core/service/auth/TokenRevocationService.java`, lines 70-116):

```java
public Uni<Boolean> isRevoked(String jti, String userId, Instant issuedAt, Instant expiresAt) {
    if (!config.enabled()) {
        return Uni.createFrom().item(false);
    }

    // Tier 0: TTL shortcut - skip check for soon-expiring tokens
    var remainingTtl = Duration.between(Instant.now(), expiresAt);
    if (remainingTtl.compareTo(config.checkThreshold()) < 0) {
        LOG.debugf("Skipping revocation check for token expiring in %s", remainingTtl);
        return Uni.createFrom().item(false);
    }

    final var hasJti = jti != null && !jti.isBlank();

    // Tier 1: Bloom filter - "definitely not revoked" check
    if (bloomFilter.isEnabled()) {
        boolean jtiNotRevoked = !hasJti || bloomFilter.definitelyNotRevoked(jti);
        boolean userNotRevoked = !config.checkUserRevocation() || bloomFilter.userDefinitelyNotRevoked(userId);

        if (jtiNotRevoked && userNotRevoked) {
            LOG.debugf("Bloom filter: token definitely not revoked (jti: %s)", jti);
            return Uni.createFrom().item(false);
        }
    }

    // Tier 2: Local cache check
    if (cache.isEnabled()) {
        if (hasJti) {
            var jtiCached = cache.isJtiRevoked(jti);
            if (jtiCached.isPresent()) {
                LOG.debugf("Cache hit: JTI revoked (jti: %s)", jti);
                return Uni.createFrom().item(jtiCached.get());
            }
        }

        if (config.checkUserRevocation()) {
            var userCached = cache.isUserRevoked(userId, issuedAt);
            if (userCached.isPresent()) {
                LOG.debugf("Cache hit: user revoked (userId: %s)", userId);
                return Uni.createFrom().item(userCached.get());
            }
        }
    }

    // Tier 3: Remote store lookup
    return checkRemoteStore(jti, userId, issuedAt);
}
```

### Tier 0: TTL Shortcut

The cheapest check is no check at all. If a token will expire within the configurable `checkThreshold` (default: 30 seconds), the system skips revocation entirely:

```java
var remainingTtl = Duration.between(Instant.now(), expiresAt);
if (remainingTtl.compareTo(config.checkThreshold()) < 0) {
    return Uni.createFrom().item(false);
}
```

This is defined in `TokenRevocationConfig` (`api/src/main/java/aussie/core/config/TokenRevocationConfig.java`, lines 42-54):

```java
/**
 * Skip revocation check for tokens expiring within this threshold.
 *
 * <p>Tokens with remaining TTL below this value skip revocation checks
 * entirely, as they will expire soon anyway. This optimization reduces
 * load on the revocation infrastructure.
 *
 * <p>Set to PT0S to always check (not recommended for high-traffic).
 */
@WithDefault("PT30S")
Duration checkThreshold();
```

**Why this matters.** At high traffic volumes, a significant fraction of tokens in flight are near expiry. If your tokens have a 15-minute TTL, a 30-second threshold skips checks for roughly 3.3% of requests. That sounds small, but at 10K RPS, that is 330 fewer checks per second -- and critically, these are tokens that will self-revoke in seconds anyway.

**What a senior might do instead.** Check every token, every time. The cost is negligible per request, so it seems unnecessary to optimize. But the senior has not thought about the failure scenario: if the remote store goes down, Tier 0 still protects near-expiry tokens from hitting the dead store, reducing the blast radius.

**Trade-off.** The threshold creates a window where a revoked token might still be accepted. For a 30-second threshold, a just-revoked token could be honored for up to 30 more seconds if its expiry was close. For security-critical revocations (compromised credentials), the caller can set token expiry far in the future, which bypasses this optimization naturally.

### Tier 1: Bloom Filter

The bloom filter is where this system earns its keep. For the 99.9%+ of tokens that are not revoked, this tier returns `false` in approximately 100 nanoseconds with zero network I/O. We will cover bloom filter mechanics in the next section.

### Tier 2: Local LRU Cache

When the bloom filter says "maybe revoked" (either a true positive or a false positive), the system checks a local LRU cache backed by Caffeine. This cache stores *confirmed* revocations that were previously verified against the remote store.

The cache is implemented in `RevocationCache` (`api/src/main/java/aussie/core/service/auth/RevocationCache.java`, lines 36-213). Two separate caches are maintained:

```java
this.jtiCache = Caffeine.newBuilder()
        .maximumSize(cacheConfig.maxSize())
        .expireAfterWrite(cacheConfig.ttl().toMillis(), TimeUnit.MILLISECONDS)
        .recordStats()
        .build();

this.userCache = Caffeine.newBuilder()
        .maximumSize(cacheConfig.maxSize() / 10) // Fewer user revocations expected
        .expireAfterWrite(cacheConfig.ttl().toMillis(), TimeUnit.MILLISECONDS)
        .recordStats()
        .build();
```

Note the sizing split: the user cache gets 1/10 the capacity of the JTI cache (lines 64-65). User-level revocations ("logout everywhere") are far less common than individual token revocations, and the cache budget should reflect that.

The JTI cache lookup (lines 79-94) includes an additional expiry check beyond Caffeine's TTL:

```java
public Optional<Boolean> isJtiRevoked(String jti) {
    if (jtiCache == null) {
        return Optional.empty();
    }

    var entry = jtiCache.getIfPresent(jti);
    if (entry != null) {
        if (entry.expiresAt().isAfter(Instant.now())) {
            return Optional.of(true);
        }
        jtiCache.invalidate(jti);
    }
    return Optional.empty();
}
```

The `Optional.empty()` return is significant. It means "not in cache" -- not "not revoked." The caller must fall through to the remote store. The cache only stores positive results (confirmed revocations), never negative results. This is a deliberate choice documented in `cacheNotRevoked()` (lines 153-158):

```java
public void cacheNotRevoked(String jti) {
    // We don't cache negative results in the current implementation
    // since the bloom filter already handles this case efficiently.
    // If we wanted to cache false positives from the bloom filter,
    // we would add a separate cache here.
}
```

**What a senior might do instead.** Cache both positive and negative results in a single map. This sounds efficient but creates a correctness hazard: if you cache "not revoked" and the token is revoked a moment later, your cache is stale and you have a false negative -- a security hole. The bloom filter already handles the "not revoked" fast path; the cache only needs to handle the "confirmed revoked" case.

### Tier 3: Remote Store

The authoritative lookup goes to the remote store via the `TokenRevocationRepository` SPI. The implementation in `checkRemoteStore()` (lines 118-152) handles JTI and user revocations as sequential checks:

```java
private Uni<Boolean> checkRemoteStore(String jti, String userId, Instant issuedAt) {
    final var hasJti = jti != null && !jti.isBlank();

    Uni<Boolean> jtiCheck = hasJti
            ? repository.isRevoked(jti).map(jtiRevoked -> {
                if (jtiRevoked) {
                    cache.cacheJtiRevocation(
                            jti, Instant.now().plus(config.cache().ttl()));
                }
                return jtiRevoked;
            })
            : Uni.createFrom().item(false);

    return jtiCheck.flatMap(jtiRevoked -> {
        if (jtiRevoked) {
            return Uni.createFrom().item(true);
        }

        if (!config.checkUserRevocation()) {
            return Uni.createFrom().item(false);
        }

        return repository.isUserRevoked(userId, issuedAt).map(userRevoked -> {
            if (userRevoked) {
                cache.cacheUserRevocation(
                        userId, issuedAt, Instant.now().plus(config.cache().ttl()));
            }
            return userRevoked;
        });
    });
}
```

Two details to note. First, when a remote lookup confirms revocation, the result is immediately cached in Tier 2 so subsequent checks for the same token skip the remote store. Second, the JTI check runs first; only if the JTI is *not* revoked does the system check user-level revocation. This is sequential, not parallel, because the common case (JTI revoked) should short-circuit the more expensive user check.

---

## Bloom Filter Mechanics

A bloom filter is a space-efficient probabilistic data structure that answers set membership queries. It can tell you with certainty that an element is *not* in the set. It can tell you with a configurable probability that an element *might* be in the set. It cannot tell you with certainty that an element *is* in the set.

This asymmetry is exactly what we need. In token revocation:

- **"Definitely not revoked"** (bloom filter negative) = safe to allow, no further checks needed
- **"Maybe revoked"** (bloom filter positive) = need to check further, could be a false positive

False positives cost performance (unnecessary cache/remote lookups). False negatives cost security (revoked tokens accepted). A bloom filter has no false negatives by construction.

### How Guava BloomFilter Works

Aussie uses Guava's `BloomFilter` implementation. The filter is a bit array. When you add an element, it is hashed through multiple hash functions, each producing a bit position. Those positions are set to 1. When you query, the same hash functions run; if all corresponding bits are 1, the element might be present. If any bit is 0, the element is definitely absent.

The filter creation is in `RevocationBloomFilter.createFilter()` (`api/src/main/java/aussie/core/service/auth/RevocationBloomFilter.java`, lines 93-95):

```java
private BloomFilter<CharSequence> createFilter(int expectedInsertions, double fpp) {
    return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), expectedInsertions, fpp);
}
```

`Funnels.stringFunnel(StandardCharsets.UTF_8)` tells Guava how to hash the input (serialize the string to bytes, then apply MurmurHash3). The `expectedInsertions` and `fpp` parameters determine the internal bit array size and number of hash functions.

### Two Separate Filters

The implementation maintains two independent bloom filters (lines 43-44):

```java
private volatile BloomFilter<CharSequence> jtiFilter;
private volatile BloomFilter<CharSequence> userFilter;
```

With different sizing (lines 84-86):

```java
this.jtiFilter = createFilter(bloomConfig.expectedInsertions(), bloomConfig.falsePositiveProbability());
this.userFilter = createFilter(bloomConfig.expectedInsertions() / 10, bloomConfig.falsePositiveProbability());
```

The user filter is sized at 1/10 of the JTI filter. User-level revocations ("revoke all tokens for user X") are far less frequent than individual token revocations, and giving them a separate, smaller filter avoids wasting the JTI filter's capacity on a different distribution of keys.

**What a senior might do instead.** Use a single filter for both JTIs and user IDs. This works but wastes capacity. If you expect 100,000 JTI revocations and 1,000 user revocations, a single filter sized for 101,000 elements allocates memory for the worst case of both. Separate filters let each one be right-sized for its workload.

### Sizing Decisions

The sizing configuration is in `TokenRevocationConfig.BloomFilterConfig` (`api/src/main/java/aussie/core/config/TokenRevocationConfig.java`, lines 77-124):

```java
/**
 * Expected number of revoked tokens to store.
 *
 * <p>Size the bloom filter appropriately for your workload:
 * <ul>
 *   <li>Small (<1K RPS): 100,000 (~1.2 MB)</li>
 *   <li>Medium (1-10K RPS): 1,000,000 (~12 MB)</li>
 *   <li>Large (>10K RPS): 10,000,000 (~120 MB)</li>
 * </ul>
 */
@WithDefault("100000")
int expectedInsertions();

/**
 * Desired false positive probability.
 *
 * <p>Trade-offs:
 * <ul>
 *   <li>0.1% (0.001): ~10 bits/element, minimal false lookups</li>
 *   <li>1% (0.01): ~7 bits/element, 10x more false lookups but 30% less memory</li>
 *   <li>0.01% (0.0001): ~13 bits/element, negligible false lookups but 30% more memory</li>
 * </ul>
 */
@WithDefault("0.001")
double falsePositiveProbability();
```

Here is the math. Guava's BloomFilter uses the formula:

- **Bits per element** = `-ln(fpp) / (ln(2)^2)` ≈ `1.44 * -log2(fpp)`
- **Total bits** = `bits_per_element * expectedInsertions`
- **Number of hash functions** = `-log2(fpp)` ≈ `bits_per_element * ln(2)`

For the default configuration (100,000 insertions, 0.1% FP rate):

| Parameter | Value |
|-----------|-------|
| Bits per element | ~10 |
| Total bits | ~1,000,000 |
| Memory | ~1.2 MB |
| Hash functions | ~7 |

The capacity planning guidance from the operational documentation (`docs/platform/token-revocation.md`, line 419) is:

> Set `expected-insertions` to `daily_revocations * max_token_ttl_days * safety_factor`.

This formula accounts for the fact that revocation entries accumulate: if you revoke 1,000 tokens per day and tokens have a 24-hour TTL, you need capacity for at most ~1,000 active revocations at any point. A 2x safety factor gives 2,000.

**Trade-off.** An undersized bloom filter degrades gracefully but expensively. The false positive rate climbs as the filter fills beyond its expected capacity, causing more requests to fall through to Tier 2 and Tier 3. This increases remote store load but does not cause incorrect behavior. An oversized filter wastes memory -- and during rebuilds, temporarily doubles the waste. The default of 100,000 is conservative for most workloads.

---

## Atomic Filter Rebuild

Bloom filters have a fundamental limitation: you cannot remove elements. Once a bit is set, it stays set. Over time, as entries accumulate and some become irrelevant (the underlying token expired), the effective false positive rate climbs. The solution is periodic rebuilds from the authoritative store.

The rebuild implementation is in `RevocationBloomFilter.rebuildFilters()` (lines 198-229):

```java
public Uni<Void> rebuildFilters() {
    if (!config.enabled() || !config.bloomFilter().enabled()) {
        return Uni.createFrom().voidItem();
    }

    var bloomConfig = config.bloomFilter();

    return repository.streamAllRevokedJtis().collect().asList().flatMap(jtis -> repository
            .streamAllRevokedUsers()
            .collect()
            .asList()
            .map(users -> {
                var newJtiFilter = createFilter(
                        Math.max(bloomConfig.expectedInsertions(), jtis.size()),
                        bloomConfig.falsePositiveProbability());
                var newUserFilter = createFilter(
                        Math.max(bloomConfig.expectedInsertions() / 10, users.size()),
                        bloomConfig.falsePositiveProbability());

                jtis.forEach(newJtiFilter::put);
                users.forEach(newUserFilter::put);

                synchronized (writeLock) {
                    this.jtiFilter = newJtiFilter;
                    this.userFilter = newUserFilter;
                    this.initialized = true;
                }

                LOG.infof("Rebuilt bloom filters: %d JTIs, %d users", jtis.size(), users.size());
                return null;
            }));
}
```

### The Swap Pattern

The rebuild process has three phases:

1. **Stream** all revoked JTIs and users from the remote store into lists.
2. **Build** new filters and populate them with the streamed data.
3. **Swap** the old filter references with the new ones atomically.

During phases 1 and 2, the old filters are still serving reads. No lock is held. No traffic is blocked. The `volatile` keyword on the filter fields (lines 43-44) ensures that when the swap happens inside the `synchronized` block, all threads immediately see the new filters on their next read.

```java
private volatile BloomFilter<CharSequence> jtiFilter;
private volatile BloomFilter<CharSequence> userFilter;
```

The `synchronized(writeLock)` block (lines 220-224) is a write lock that coordinates concurrent rebuilds and incremental additions. Reads never acquire this lock. The `volatile` provides the happens-before guarantee for cross-thread visibility.

### Adaptive Sizing

Note the `Math.max` on lines 211 and 214:

```java
var newJtiFilter = createFilter(
        Math.max(bloomConfig.expectedInsertions(), jtis.size()),
        bloomConfig.falsePositiveProbability());
```

If the actual number of revoked entries exceeds the configured `expectedInsertions`, the filter is upsized to fit. This prevents the false positive rate from exceeding the configured threshold when the configuration is stale or undersized. The downside is memory: if you have 500,000 active revocations but configured 100,000, the rebuild will allocate a filter sized for 500,000 -- roughly 6 MB instead of 1.2 MB.

### Memory Impact

During rebuild, both the old and new filters exist simultaneously. This means temporary 2x memory usage, as documented in the platform operations guide (`docs/platform/token-revocation.md`, lines 396-403):

| Resource | Impact |
|----------|--------|
| **Memory** | 2x bloom filter memory temporarily (old + new coexist) |
| **Network** | Full scan of revocation store |
| **CPU** | Hashing all entries into new filter (~10us per entry) |

For the default 100,000 insertions at 0.1% FP rate, this means ~2.4 MB temporarily. For the large configuration (10,000,000 insertions), this means ~240 MB temporarily -- enough to cause OOM on an undersized container.

### Startup Behavior

On startup, `RevocationBloomFilter.init()` (lines 59-81) does two things immediately and one thing asynchronously:

```java
@PostConstruct
void init() {
    if (!config.enabled() || !config.bloomFilter().enabled()) {
        LOG.info("Bloom filter disabled, skipping initialization");
        return;
    }

    // Initialize empty filters immediately for fast startup
    initializeEmptyFilters();

    // Rebuild from remote store in background
    rebuildFilters()
            .subscribe()
            .with(
                    v -> LOG.info("Initial bloom filter rebuild completed"),
                    e -> LOG.warnf(e, "Initial bloom filter rebuild failed, using empty filters"));

    // Schedule periodic rebuilds
    schedulePeriodicRebuild();

    // Subscribe to revocation events from other instances
    subscribeToRevocationEvents();
}
```

Empty filters are created synchronously so the gateway can start accepting traffic immediately. A full rebuild runs in the background. Until the rebuild completes, the empty filters will return `mightContain() == false` for every input, meaning the bloom filter says "definitely not revoked" for every token. This means every request takes the Tier 1 fast path and never reaches the remote store.

This is a security risk. We will cover it in the Failure Scenarios section.

**What a senior might do instead.** Block startup until the initial rebuild completes. This is safer but violates the non-blocking startup principle and could cause cascading failures if the remote store is slow or unavailable. The Aussie approach trades immediate security for startup reliability, relying on the background rebuild to close the window quickly.

---

## Pub/Sub Synchronization

In a multi-instance deployment, a revocation on instance A needs to reach instance B. Without synchronization, instance B will not learn about the revocation until its next periodic bloom filter rebuild (default: 1 hour).

### Architecture

Aussie uses a two-pronged synchronization strategy:

1. **Real-time**: Revocation events are published via the `RevocationEventPublisher` SPI to all instances. Each instance adds the revoked entry to its local bloom filter immediately.
2. **Periodic**: Each instance independently rebuilds its bloom filter from the authoritative store on a configurable interval.

The real-time path handles the common case. The periodic rebuild is a consistency safeguard that corrects any missed events (pub/sub is best-effort) and cleans up expired entries that cannot be removed from a bloom filter.

### Event Model

The event types are defined as a sealed interface in `RevocationEvent` (`api/src/main/java/aussie/core/model/auth/RevocationEvent.java`):

```java
public sealed interface RevocationEvent {

    record JtiRevoked(String jti, Instant expiresAt) implements RevocationEvent {}

    record UserRevoked(String userId, Instant issuedBefore, Instant expiresAt) implements RevocationEvent {}
}
```

The `sealed` keyword ensures that these are the only two event types. Pattern matching in the event handler (lines 121-131 in `RevocationBloomFilter.java`) is exhaustive without a default case:

```java
private void handleRevocationEvent(RevocationEvent event) {
    switch (event) {
        case RevocationEvent.JtiRevoked jtiRevoked -> {
            addRevokedJti(jtiRevoked.jti());
            LOG.debugf("Added JTI to bloom filter from event: %s", jtiRevoked.jti());
        }
        case RevocationEvent.UserRevoked userRevoked -> {
            addRevokedUser(userRevoked.userId());
            LOG.debugf("Added user to bloom filter from event: %s", userRevoked.userId());
        }
    }
}
```

### The Publisher SPI

The `RevocationEventPublisher` interface (`api/src/main/java/aussie/core/port/out/RevocationEventPublisher.java`) defines three operations:

```java
public interface RevocationEventPublisher {

    Uni<Void> publishJtiRevoked(String jti, Instant expiresAt);

    Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt);

    Multi<RevocationEvent> subscribe();
}
```

The Javadoc (lines 16-18) makes the delivery guarantee explicit:

> Events should be delivered to all subscribed instances. Delivery should be best-effort (revocation check falls back to remote store).

This is a deliberate choice. Pub/sub is an optimization, not a correctness requirement. If pub/sub fails, the periodic rebuild ensures eventual consistency. If both pub/sub and the periodic rebuild fail, the remote store lookup in Tier 3 is always available as a fallback.

### Redis Reference Implementation

The Redis implementation (`api/src/main/java/aussie/adapter/out/storage/redis/RedisRevocationEventPublisher.java`) uses Redis pub/sub with a simple colon-delimited message format:

```java
@Override
public Uni<Void> publishJtiRevoked(String jti, Instant expiresAt) {
    if (!config.pubsub().enabled()) {
        return Uni.createFrom().voidItem();
    }

    var message = "jti" + MESSAGE_SEPARATOR + jti + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

    return Uni.createFrom()
            .item(() -> {
                pubsub.publish(channel, message);
                LOG.debugf("Published JTI revocation event: %s", jti);
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .replaceWithVoid();
}
```

The message format is deliberately simple: `jti:abc123:1705312200000` or `user:user-123:1705312200000:1705398600000`. No JSON. No schema registry. The tradeoff is that the format is fragile (what if a JTI contains a colon?), but the simplicity means zero serialization overhead and trivial parsing.

The `MessageHandler` inner class (lines 129-184) uses a `BroadcastProcessor` to convert Redis pub/sub messages into a Mutiny `Multi<RevocationEvent>`:

```java
private static class MessageHandler implements java.util.function.Consumer<String> {

    private final BroadcastProcessor<RevocationEvent> processor =
            BroadcastProcessor.create();

    @Override
    public void accept(String message) {
        try {
            var event = parseMessage(message);
            if (event != null) {
                processor.onNext(event);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse revocation event: %s", message);
        }
    }
    // ...
}
```

Note the `@Startup` annotation on the publisher class (line 36). This ensures the Redis pub/sub subscription is established eagerly at startup, not lazily on first use. Without this, the first revocation event could block the event loop while the subscription is being established.

### What Happens When Pub/Sub Goes Down

When the Redis pub/sub connection drops, revocations on one instance stop propagating to others in real time. The failure mode documentation (`docs/platform/token-revocation.md`, lines 408-416) spells this out:

| Scenario | Behavior |
|----------|----------|
| Pub/sub connection lost | New revocations not enforced until next rebuild (up to `rebuild-interval`) |

The maximum window of vulnerability equals the `rebuild-interval` (default: 1 hour). In practice, with pub/sub enabled, the documentation (line 406) recommends lengthening the rebuild interval to 6 hours since pub/sub handles the real-time case.

If pub/sub reliability is critical to your deployment, there are two mitigations:

1. **Shorten `rebuild-interval`** to 5-10 minutes. This caps the vulnerability window but increases remote store load.
2. **Use a more durable transport** (e.g., implement `RevocationEventPublisher` with Kafka instead of Redis pub/sub). Kafka provides message durability and consumer group semantics, so missed messages are retried.

---

## SPI Design

The token revocation subsystem is designed around two SPI (Service Provider Interface) contracts. This is how Aussie separates *policy* (the tiered caching strategy) from *mechanism* (where revocations are stored and how events are distributed).

### TokenRevocationRepository

The storage SPI is defined in `api/src/main/java/aussie/spi/TokenRevocationRepository.java`:

```java
public interface TokenRevocationRepository {

    Uni<Void> revoke(String jti, Instant expiresAt);

    Uni<Boolean> isRevoked(String jti);

    Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt);

    Uni<Boolean> isUserRevoked(String userId, Instant issuedAt);

    Multi<String> streamAllRevokedJtis();

    Multi<String> streamAllRevokedUsers();
}
```

Six methods. Every return type is either `Uni` or `Multi` -- all non-blocking. The Javadoc (lines 9-20) specifies the contract:

> **Implementation Requirements:**
> - Entries MUST expire automatically based on the provided TTL
> - All operations MUST be non-blocking (return Uni/Multi)
> - Implementations SHOULD be thread-safe
> - Implementations SHOULD handle connection failures gracefully

The distinction between MUST and SHOULD is deliberate. TTL expiration and non-blocking I/O are hard requirements because the bloom filter rebuild and the request path depend on them. Thread safety and graceful failure handling are best practices but do not break the system if omitted (the caller handles failures).

### Registration via CDI

The Javadoc also shows how platform teams register custom implementations (lines 22-31):

```java
/**
 * Platform teams register custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class MemcachedTokenRevocationRepository implements TokenRevocationRepository {
 *     // Custom implementation using AWS ElastiCache Memcached
 * }
 * }</pre>
 */
```

The Redis implementation uses `@DefaultBean` (line 45 of `RedisTokenRevocationRepository.java`), which means it is automatically overridden by any `@Alternative` with higher priority. Platform teams do not need to modify any Aussie code to swap the storage backend.

### Redis Reference Implementation

The Redis implementation (`api/src/main/java/aussie/adapter/out/storage/redis/RedisTokenRevocationRepository.java`) demonstrates the key patterns.

**JTI storage** (lines 69-83): Simple key-value with TTL.

```java
@Override
public Uni<Void> revoke(String jti, Instant expiresAt) {
    var key = JTI_PREFIX + jti;
    var ttlSeconds = calculateTtl(expiresAt);

    if (ttlSeconds <= 0) {
        LOG.debugf("Skipping revocation for already-expired token: %s", jti);
        return Uni.createFrom().voidItem();
    }

    var operation = valueCommands
            .setex(key, ttlSeconds, REVOKED_VALUE)
            .replaceWithVoid()
            .invoke(() -> LOG.debugf("Revoked token in Redis: %s (TTL: %ds)", jti, ttlSeconds));
    return timeoutHelper.withTimeoutSilent(operation, "revoke");
}
```

**Fail-closed on reads** (lines 86-94): This is a security-critical decision. When the Redis lookup times out, the implementation treats the token as revoked:

```java
@Override
public Uni<Boolean> isRevoked(String jti) {
    var key = JTI_PREFIX + jti;
    var operation = keyCommands.exists(key);
    // Fail-closed: return true on timeout (treat as revoked for security)
    return timeoutHelper.withTimeoutFallback(operation, "isRevoked", () -> {
        LOG.warnf("Revocation check timed out for jti %s, treating as revoked (fail-closed)", jti);
        return true;
    });
}
```

This is the opposite of what many systems do. The SPI documentation (`docs/platform/token-revocation-spi.md`, lines 119-138) presents both options:

**Fail-Open (default for custom implementations):** Token validation succeeds if the revocation check fails.
**Fail-Closed (the Redis reference implementation):** Token validation fails if the revocation check fails.

The reference implementation chose fail-closed because the gateway is a security boundary. A false rejection (user re-authenticates) is preferable to a false acceptance (revoked token honored). Custom implementations can make a different choice.

**Streaming for bloom filter rebuild** (lines 143-152): Uses Redis SCAN (not KEYS) for production-safe key iteration:

```java
@Override
public Multi<String> streamAllRevokedJtis() {
    var args = new KeyScanArgs().match(JTI_PREFIX + "*").count(1000);
    return keyCommands.scan(args).toMulti().map(key -> key.substring(JTI_PREFIX.length()));
}
```

SCAN returns results incrementally with cursor-based pagination. KEYS would block Redis for the entire scan, potentially causing a latency spike across all Redis clients. The `count(1000)` hint suggests returning approximately 1,000 keys per scan iteration.

### Contract Tests for SPI Implementors

The abstract contract test (`api/src/test/java/aussie/spi/TokenRevocationRepositoryContractTest.java`) verifies nine behaviors grouped into four categories:

| Test | Description |
|------|-------------|
| `revoke() then isRevoked()` | Basic revocation works |
| `isRevoked() for unknown JTI` | Returns false for unknown tokens |
| `multiple revocations` | All revocations tracked |
| `revokeAllForUser() affects old tokens` | Tokens before cutoff are revoked |
| `revokeAllForUser() doesn't affect new tokens` | Tokens after cutoff are valid |
| `isUserRevoked() for unknown user` | Returns false for unknown users |
| `streamAllRevokedJtis()` | Returns all revoked JTIs |
| `streamAllRevokedUsers()` | Returns all users with revocations |
| JTI/user revocation isolation | Revocation types don't interfere |

Platform teams extend this abstract test and implement a single method:

```java
class MyCustomRepositoryContractTest extends TokenRevocationRepositoryContractTest {

    @Override
    protected TokenRevocationRepository createRepository() {
        return new MyCustomRepository(config);
    }
}
```

The isolation tests (lines 229-266) deserve special attention. They verify that revoking a JTI does not cause a user-level revocation, and vice versa. This catches a common implementation bug: using the same key space for both JTI and user entries.

**What a senior might do instead.** Ship the Redis implementation and call it done. Platform teams that need a different backend can "just implement the interface." Without contract tests, those implementations would be verified only by integration tests in production -- exactly the place where you want the fewest surprises.

---

## Failure Scenarios

The tiered architecture creates resilience, but each tier has its own failure modes. Understanding them is the difference between staff-level thinking and hoping for the best.

### Store Unavailable at Startup

This is the most dangerous failure mode. On startup, `RevocationBloomFilter.init()` creates empty filters synchronously (line 67) and kicks off a rebuild in the background (lines 70-74):

```java
// Initialize empty filters immediately for fast startup
initializeEmptyFilters();

// Rebuild from remote store in background
rebuildFilters()
        .subscribe()
        .with(
                v -> LOG.info("Initial bloom filter rebuild completed"),
                e -> LOG.warnf(e, "Initial bloom filter rebuild failed, using empty filters"));
```

If the remote store is unreachable, the rebuild fails and the bloom filter remains empty. An empty bloom filter says "definitely not revoked" for *every* token. This means:

- **Tier 1 always returns "not revoked"** -- no request ever reaches Tier 2 or Tier 3.
- **Every revoked token is accepted** until the next successful rebuild.

The operational documentation (`docs/platform/token-revocation.md`, line 412) flags this explicitly:

> Store unavailable at startup: Empty filter used; **all tokens pass as not revoked** (security risk)

**Mitigation options:**

1. **Block startup until initial rebuild succeeds.** Safest, but can cause cascading failures if the store is down during a rolling deployment.
2. **Disable the bloom filter until first successful rebuild.** When `bloomFilter.isEnabled()` returns `false`, the service skips Tier 1 and goes directly to Tier 2/3. This is what happens when `initialized` is `false` -- the conservative path treats everything as "might be revoked." But note that `initializeEmptyFilters()` sets `initialized = true` immediately (line 87), which means the empty filter *does* serve requests. If you want the conservative fallback, you would need to defer setting `initialized` until the first rebuild succeeds.
3. **Health check integration.** Mark the instance as unhealthy until the first rebuild completes, so the load balancer does not route traffic to it.

### Store Unavailable During Periodic Rebuild

When the remote store is unreachable during a scheduled rebuild, the failure is logged and the previous filter continues serving:

```java
vertx.setPeriodic(interval.toMillis(), id -> rebuildFilters()
        .subscribe()
        .with(
                v -> LOG.debug("Scheduled bloom filter rebuild completed"),
                e -> LOG.warnf(e, "Scheduled bloom filter rebuild failed")));
```

The `e -> LOG.warnf(...)` handler catches the failure and the old filter stays in place. This is the correct behavior: a stale filter with slightly elevated false positive rates is far better than an empty filter that lets everything through.

### Pub/Sub Connection Loss

As covered in the Synchronization section, pub/sub loss means revocations stop propagating between instances. The maximum vulnerability window equals the `rebuild-interval`. The system does not detect pub/sub loss proactively -- it relies on the periodic rebuild to re-synchronize.

### Out of Memory During Rebuild

The `rebuildFilters()` method collects all revoked JTIs into a list before building the new filter (lines 205-208):

```java
return repository.streamAllRevokedJtis().collect().asList().flatMap(jtis -> repository
        .streamAllRevokedUsers()
        .collect()
        .asList()
        .map(users -> { ... }));
```

If there are millions of revoked entries, the list plus the new filter can exceed available heap. During the build phase, both the old filter and the new filter (plus the intermediate lists) exist simultaneously.

For 1,000,000 revocations:
- Two lists of strings: ~50 MB (assuming ~50 bytes per JTI string)
- New JTI filter: ~12 MB
- Old JTI filter (still serving): ~12 MB
- Total peak: ~74 MB

For 10,000,000 revocations: ~740 MB peak. On a 512 MB container, this triggers OOM.

**Mitigation:** Reduce `expectedInsertions`, increase heap, or implement a streaming rebuild that does not collect into a list. The current implementation chose list collection for simplicity (Guava's `BloomFilter.put()` is synchronous and does not support streaming construction from a reactive pipeline directly).

---

## Performance Targets

The service's Javadoc declares explicit performance targets (`TokenRevocationService.java`, lines 27-33):

| Metric | Target | Tier |
|--------|--------|------|
| P50 latency | < 100us | Bloom filter hit (Tier 0/1) |
| P99 latency | < 500us | Local cache hit (Tier 2) |
| P99.9 latency | < 5ms | Remote lookup (Tier 3) |

These targets assume that the vast majority of requests are for non-revoked tokens, which the bloom filter handles in ~100ns with no network I/O. The P99 accounts for bloom filter false positives that fall through to the local cache. The P99.9 accounts for cache misses that require a remote store lookup.

**How these targets map to the tiers:**

| Tier | Operation | Expected Latency | Expected Hit Rate |
|------|-----------|------------------|--------------------|
| 0 | TTL comparison | <10ns | 1-5% of requests (near-expiry tokens) |
| 1 | Bloom filter lookup | ~100ns | ~99.9% of remaining requests (not revoked) |
| 2 | Caffeine cache lookup | ~1us | Variable (previously confirmed revocations) |
| 3 | Redis round-trip | 1-5ms | <0.1% of requests (bloom filter false positives + first-time revoked tokens) |

The key insight is that the bloom filter eliminates network I/O for the common case. At 10,000 RPS with a 0.1% false positive rate, approximately 10 requests per second fall through to the remote store. The other 9,990 are answered locally in nanoseconds.

---

## Comparison: Revocation Strategies

Different systems make different trade-offs. Here is how the four common approaches compare:

| Strategy | Latency (P50) | Latency (P99.9) | Consistency | Memory | Complexity | Security Risk |
|----------|---------------|------------------|-------------|--------|------------|---------------|
| **Short-lived tokens only** | 0 (no check) | 0 (no check) | N/A (tokens self-expire) | None | Minimal | Revocation window = token TTL (minutes) |
| **Bloom filter tiered cache** (Aussie) | ~100ns | ~5ms | Eventual (seconds to minutes) | 1-120 MB per instance | High | Empty filter on startup; pub/sub gap |
| **Centralized validation service** | 1-5ms | 10-50ms | Strong (every request hits the service) | Centralized | Medium | Single point of failure; latency on every request |
| **CRL-style lists** | ~1us (local list lookup) | ~1us | Batch (CRL refresh interval) | Proportional to revocation count | Medium | Stale CRL = stale revocations |

### Short-lived tokens only

Issue tokens with a 5-minute TTL. When you need to "revoke," just stop refreshing. The token dies naturally. This is the simplest approach and works well for systems where the revocation window (minutes) is acceptable. Many authentication systems use this as the default. The downside: for security-critical revocations (compromised credentials), waiting 5 minutes is too long.

### Bloom filter tiered cache (Aussie's approach)

Adds a fast local check that eliminates remote lookups for non-revoked tokens. Excellent latency profile for the common case. The complexity cost is real: bloom filter sizing, atomic rebuilds, pub/sub synchronization, and the failure scenarios described above. Appropriate when you need sub-millisecond revocation checks at scale with acceptable eventual consistency.

### Centralized validation service

Every token validation calls a remote service that maintains the revocation list. Strong consistency -- a revoked token is rejected on the very next request. But every request pays the network latency tax. At high scale, this service becomes a bottleneck and a single point of failure. A senior engineer often starts here because it is conceptually simple. A staff engineer recognizes that the latency is unacceptable for a gateway in the hot path.

### CRL-style lists (Certificate Revocation List pattern)

Periodically fetch a complete list of revoked tokens from a central authority and store it locally. Similar to the bloom filter approach but without the probabilistic element -- you have the exact list. Works well when revocation volumes are low. Breaks down when there are millions of revocations (the list becomes too large to transfer and store).

### When to use each

- **Short-lived tokens only**: When your revocation SLA allows a window equal to the token TTL. The right default for most systems.
- **Bloom filter tiered cache**: When you need immediate revocation (seconds, not minutes) at gateway-level latency with thousands of RPS. The extra complexity is justified.
- **Centralized validation service**: When strong consistency is required and latency budgets are generous (>5ms per request). Simpler to reason about than eventual consistency.
- **CRL-style lists**: When revocation volumes are low (< 10,000 active revocations) and batch refresh intervals (minutes) are acceptable.

---

## Key Source Files

| File | Path | Purpose |
|------|------|---------|
| `TokenRevocationService.java` | `api/src/main/java/aussie/core/service/auth/TokenRevocationService.java` | Tiered revocation check orchestration |
| `RevocationBloomFilter.java` | `api/src/main/java/aussie/core/service/auth/RevocationBloomFilter.java` | Bloom filter management, atomic rebuild, pub/sub subscription |
| `RevocationCache.java` | `api/src/main/java/aussie/core/service/auth/RevocationCache.java` | Local LRU cache for confirmed revocations |
| `TokenRevocationConfig.java` | `api/src/main/java/aussie/core/config/TokenRevocationConfig.java` | Configuration interface with defaults and sizing guidance |
| `TokenRevocationRepository.java` | `api/src/main/java/aussie/spi/TokenRevocationRepository.java` | Storage SPI for platform teams |
| `RevocationEventPublisher.java` | `api/src/main/java/aussie/core/port/out/RevocationEventPublisher.java` | Pub/sub SPI for multi-instance sync |
| `RevocationEvent.java` | `api/src/main/java/aussie/core/model/auth/RevocationEvent.java` | Sealed event types for pub/sub |
| `RedisTokenRevocationRepository.java` | `api/src/main/java/aussie/adapter/out/storage/redis/RedisTokenRevocationRepository.java` | Redis reference implementation (fail-closed) |
| `RedisRevocationEventPublisher.java` | `api/src/main/java/aussie/adapter/out/storage/redis/RedisRevocationEventPublisher.java` | Redis pub/sub reference implementation |
| `TokenRevocationRepositoryContractTest.java` | `api/src/test/java/aussie/spi/TokenRevocationRepositoryContractTest.java` | Abstract contract tests for SPI implementors |
| `TokenRevocationServiceTest.java` | `api/src/test/java/aussie/core/service/auth/TokenRevocationServiceTest.java` | Unit tests for tiered revocation logic |
| `RevocationBloomFilterTest.java` | `api/src/test/java/aussie/core/service/auth/RevocationBloomFilterTest.java` | Unit tests for bloom filter operations |
| `token-revocation.md` | `docs/platform/token-revocation.md` | Operational guide for platform teams |
| `token-revocation-spi.md` | `docs/platform/token-revocation-spi.md` | SPI implementation guide for custom backends |
