# Chapter 7: Reactive Programming -- Non-Blocking as an Architectural Constraint

A gateway is a fundamentally different kind of application. It does almost no computation. It receives a request, looks up where to send it, waits for the upstream to respond, and relays the answer back. The entire value proposition is in the waiting. This chapter explains how Aussie treats non-blocking I/O not as an optimization technique but as a structural constraint that shapes every layer of the codebase.

---

## 7.1 Why Non-Blocking

### The Problem with Threads

A traditional servlet container allocates one thread per request. Each thread blocks while waiting for the upstream service to respond. At 200 concurrent connections, you have 200 threads doing nothing but occupying memory (typically 1 MB of stack per thread) and burning OS context-switch overhead. At 2,000 connections the model collapses: you either run out of memory or spend more time switching between threads than doing actual work.

A gateway is the worst case for this model. Unlike an application server that might spend 50% of request time computing a response, a gateway spends 95% or more of its time waiting on network I/O. Thread-per-request means paying the full cost of thread management for the least computationally demanding workload possible.

### The Vert.x Event Loop

Quarkus builds on Vert.x, which uses a small number of event loop threads -- typically one per CPU core. A single event loop can service thousands of concurrent connections because it never waits. It registers interest in I/O events (a socket becoming readable, a timer firing, a DNS response arriving) and processes them as they occur. The contract is simple and absolute: **never block the event loop**.

This is the core architectural constraint that drives every design decision in this chapter. If you block an event loop thread -- even for 50 milliseconds to make a synchronous database call -- you stall every other connection managed by that thread. In a gateway handling thousands of connections per event loop, a single blocking call can cascade into a noticeable latency spike across hundreds of unrelated requests.

Aussie enforces this by using `Uni<T>` as its universal return type for any operation that might involve I/O. The type system makes the contract visible: if a method returns `Uni<T>`, it promises not to block.

**What a senior might do instead**: reach for `CompletableFuture<T>` from the JDK standard library. The Mutiny `Uni<T>` type adds lazy evaluation (nothing happens until subscription), built-in composition operators, and integration with Quarkus's context propagation. `CompletableFuture` is eagerly evaluated on creation, which makes patterns like deferred computation and request coalescing (Sections 7.4 and 7.5) significantly harder to implement correctly.

---

## 7.2 Uni<T> vs Multi<T>

SmallRye Mutiny provides two reactive types. Understanding when to use each is essential.

### Uni<T>: Single Asynchronous Result

`Uni<T>` represents an operation that will eventually produce exactly one value (or fail). This is the workhorse of the gateway. Every HTTP request produces one response. Every token validation yields one result. Every cache lookup returns one value or nothing.

Every port interface in Aussie returns `Uni<T>`:

```java
// api/src/main/java/aussie/core/port/out/ProxyClient.java, lines 8-11
public interface ProxyClient {

    Uni<ProxyResponse> forward(PreparedProxyRequest request);
}
```

```java
// api/src/main/java/aussie/core/port/in/GatewayUseCase.java, lines 14-23
public interface GatewayUseCase {

    /**
     * Forward a request through the gateway.
     *
     * @param request the gateway request containing path, method, headers, and body
     * @return the gateway result indicating success, authentication failure, or error
     */
    Uni<GatewayResult> forward(GatewayRequest request);
}
```

The consistency matters. When every I/O boundary uses `Uni<T>`, the compiler enforces the non-blocking contract. You cannot accidentally call `.get()` on a `Uni` and block -- the API does not have a `.get()` method. You must compose operations using the operators described in Section 7.3.

### Multi<T>: Asynchronous Stream

`Multi<T>` represents a stream of zero or more items emitted over time. Aussie uses it sparingly, only where stream semantics are genuinely needed:

```java
// api/src/main/java/aussie/core/service/auth/TokenRevocationService.java, lines 280-291
public Multi<String> streamAllRevokedJtis() {
    return repository.streamAllRevokedJtis();
}

public Multi<String> streamAllRevokedUsers() {
    return repository.streamAllRevokedUsers();
}
```

The in-memory implementation shows `Multi<T>` wrapping an iterable:

```java
// api/src/main/java/aussie/adapter/out/storage/memory/InMemoryTokenRevocationRepository.java, lines 100-107
@Override
public Multi<String> streamAllRevokedJtis() {
    return Multi.createFrom().iterable(revokedJtis.keySet());
}

@Override
public Multi<String> streamAllRevokedUsers() {
    return Multi.createFrom().iterable(revokedUsers.keySet());
}
```

These are used for administrative operations like bloom filter rebuilds, where the data set might be large enough that loading it all into memory at once is undesirable. The stream allows backpressure-aware processing of thousands of revoked token IDs without buffering them all in a single `List`.

**Trade-off**: `Multi<T>` introduces complexity around backpressure, cancellation, and error handling that `Uni<T>` avoids. The codebase deliberately limits `Multi<T>` to a handful of streaming use cases. For the typical request/response flow, `Uni<T>` is simpler and sufficient. If you find yourself reaching for `Multi<T>` in request-processing code, reconsider whether you actually need stream semantics or whether a `Uni<List<T>>` would be clearer.

---

## 7.3 Composition Patterns

Reactive code is built by composing small operations into pipelines. Mutiny provides several operators for this, and understanding the distinction between them is critical. The names look similar but their semantics differ in ways that matter for correctness.

### map: Synchronous Transform

`map` transforms the value inside a `Uni` without introducing any asynchronous operation. The transformation function runs on the same thread, inline.

```java
// api/src/main/java/aussie/core/service/gateway/GatewayService.java, lines 144-148
return proxyClient
        .forward(preparedRequest)
        .map(response -> (GatewayResult) GatewayResult.Success.from(response))
        .onFailure()
        .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
```

Here `map` converts a `ProxyResponse` into a `GatewayResult.Success`. This is a pure, synchronous data transformation -- no I/O, no waiting. Use `map` when your transformation function is cheap and does not itself return a `Uni`.

### flatMap: Async Composition

`flatMap` is for chaining operations where the transformation itself is asynchronous. The function you pass to `flatMap` returns a `Uni<T>`, and `flatMap` "flattens" the resulting `Uni<Uni<T>>` into a plain `Uni<T>`.

The `GatewayService.forward()` method demonstrates multi-stage `flatMap` composition:

```java
// api/src/main/java/aussie/core/service/gateway/GatewayService.java, lines 68-93
@Override
public Uni<GatewayResult> forward(GatewayRequest request) {
    final long startTime = System.nanoTime();

    return serviceRegistry.findRouteAsync(request.path(), request.method()).flatMap(routeResult -> {
        if (routeResult.isEmpty()) {
            var result = new GatewayResult.RouteNotFound(request.path());
            metrics.recordGatewayResult(null, result);
            return Uni.createFrom().item(result);
        }

        if (!(routeResult.get() instanceof RouteMatch routeMatch)) {
            var result = new GatewayResult.RouteNotFound(request.path());
            metrics.recordGatewayResult(null, result);
            return Uni.createFrom().item(result);
        }

        var service = routeMatch.service();

        return routeAuthService
                .authenticate(request, routeMatch)
                .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch))
                .invoke(result -> recordMetrics(request, service, result, startTime));
    });
}
```

This method chains three asynchronous operations:

1. **Route lookup** (`serviceRegistry.findRouteAsync`) -- may refresh from persistent storage
2. **Authentication** (`routeAuthService.authenticate`) -- may validate JWTs, fetch JWKS, check sessions
3. **Proxying** (inside `handleAuthResult`) -- forwards the request to the upstream service

Each step depends on the result of the previous step. The `flatMap` operator expresses these dependencies without nesting callbacks. Read the code from top to bottom: find route, authenticate, handle auth result, record metrics.

### chain vs flatMap

`chain` is semantically identical to `flatMap` -- it chains a `Uni`-returning function. It exists as an alias to read better in certain contexts. The `ServiceRegistry` uses `chain` for fire-and-forget side effects:

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java, lines 269-273
return repository
        .save(service)
        .invoke(() -> compileAndCacheRoutes(service))
        .call(() -> cache.put(service))
        .map(v -> RegistrationResult.success(service));
```

Note `call()` here, which is similar to `chain` but discards the result. It says: "execute this side effect and wait for it to complete, but keep using the previous value." This is the right operator for cache writes where you need to wait for completion but do not need the result.

### invoke: Synchronous Side Effect

`invoke` runs a synchronous side effect without altering the pipeline value. It is the non-blocking equivalent of adding a log statement or metrics call:

```java
// api/src/main/java/aussie/core/service/gateway/GatewayService.java, line 92
.invoke(result -> recordMetrics(request, service, result, startTime));
```

The `recordMetrics` method is synchronous (it calls `System.nanoTime()` and records counters). Using `invoke` keeps it in the pipeline without changing the `GatewayResult` flowing through.

### onFailure().recoverWithItem: Error Recovery

This pattern catches failures in the reactive pipeline and converts them to a normal result:

```java
// api/src/main/java/aussie/core/service/gateway/GatewayService.java, lines 144-148
return proxyClient
        .forward(preparedRequest)
        .map(response -> (GatewayResult) GatewayResult.Success.from(response))
        .onFailure()
        .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
```

If the proxy call throws (connection refused, timeout, upstream error), the failure is caught and converted to a `GatewayResult.Error`. The caller never sees an exception -- it gets a typed result it can match on. This pattern appears identically in both `GatewayService` and `PassThroughService` (lines 129-133 and 139-143 of `PassThroughService.java`), enforcing the invariant that the gateway always returns a structured result, never an unhandled exception.

**What a senior might do instead**: use `onFailure().recoverWithUni()` to perform an async fallback (retry with a different upstream, for example). Aussie chooses the simpler `recoverWithItem` because its error-handling strategy is to report the failure immediately rather than attempt retries at the gateway layer.

### Recursive flatMap for Iterative Validation

The `TokenValidationService` demonstrates a pattern worth studying: recursive `flatMap` for trying multiple providers in sequence:

```java
// api/src/main/java/aussie/core/service/auth/TokenValidationService.java, lines 114-128
private Uni<TokenValidationResult> validateWithProviders(
        String token, List<TokenProviderConfig> configs, int index) {
    if (index >= configs.size()) {
        return Uni.createFrom().item(new TokenValidationResult.Invalid("Token not accepted by any provider"));
    }

    var config = configs.get(index);
    return validateWithValidators(token, config, 0).flatMap(result -> {
        if (result instanceof TokenValidationResult.Valid) {
            return Uni.createFrom().item(result);
        }
        return validateWithProviders(token, configs, index + 1);
    });
}
```

This is the reactive equivalent of a for-loop with early return. Each iteration is a `flatMap` that either short-circuits on success or recurses to the next provider. The recursion is safe because it is not stack recursion -- each `flatMap` returns a new `Uni` that will be subscribed to asynchronously. Even with 100 providers, you would not blow the stack.

After signature validation succeeds, there is a further async chain for revocation checking:

```java
// api/src/main/java/aussie/core/service/auth/TokenValidationService.java, lines 136-144
return validator.validate(token, config).flatMap(result -> {
    if (result instanceof TokenValidationResult.Valid valid) {
        LOG.debugv("Token validated by {0} for issuer {1}", validator.name(), config.issuer());
        return checkRevocation(valid);
    }
    return validateWithValidators(token, config, index + 1);
});
```

This demonstrates a key reactive composition pattern: early termination is expressed through conditional `flatMap`, not through `break` or `return` statements.

---

## 7.4 Deferred for Lazy Evaluation

`Uni.createFrom().deferred()` delays the creation of the inner `Uni` until subscription time. This subtle but powerful distinction is the foundation of the request coalescing pattern.

### Why Deferred Matters

Consider the `ensureCacheFresh()` method in `ServiceRegistry`:

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java, lines 138-169
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

Without `deferred`, the lambda would execute immediately when the `Uni` is constructed. With `deferred`, the lambda executes when the `Uni` is subscribed to -- which happens on the event loop thread handling a specific request.

This matters because multiple requests may call `ensureCacheFresh()` concurrently. The `deferred` block acts as a decision point at subscription time: "Is the cache still stale? Is someone already refreshing? Should I start a new refresh or join the existing one?" These decisions must be made at the moment of subscription, not at the moment the `Uni` object is created (which might be on a different thread, at a different time).

The double-check pattern (checking `isCacheStale()` both before and inside the `deferred` block) is deliberate. The first check is a fast path that avoids even creating the `deferred` wrapper. The second check, inside the block, guards against races where another thread refreshed the cache between the first check and subscription.

### The JwksCacheService Pattern

The same `deferred` pattern appears in `JwksCacheService`:

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java, lines 87-93
private Uni<JsonWebKeySet> getOrCreateFetch(URI jwksUri) {
    return Uni.createFrom().deferred(() -> {
        var fetch = inFlightFetches.computeIfAbsent(jwksUri, this::createFetch);
        return fetch;
    });
}
```

Here `deferred` ensures that `computeIfAbsent` is called at subscription time. This means the JWKS fetch is created only when actually needed, and `ConcurrentHashMap.computeIfAbsent` provides the atomicity guarantee that only one fetch is created per URI.

**Trade-off**: `deferred` adds a level of indirection. The code inside the block cannot be reasoned about at construction time -- its behavior depends on the state of the system when subscription occurs. This makes debugging harder because you cannot simply set a breakpoint on the line where the `Uni` is created and see what value it will produce. You must trace through to the subscription point. Use `deferred` only when lazy evaluation is genuinely required, not as a default.

---

## 7.5 Memoization

`memoize().indefinitely()` makes a `Uni` "hot": after the first subscription triggers computation, subsequent subscribers receive the cached result without re-triggering the computation. This is the mechanism that prevents duplicate work in cache refresh scenarios.

### Request Coalescing

The `ServiceRegistry` combines `deferred` and `memoize` to implement request coalescing:

```java
// api/src/main/java/aussie/core/service/routing/ServiceRegistry.java, lines 156-160
var refresh = refreshRouteCache()
        .onTermination()
        .invoke(() -> inFlightRefresh.set(null))
        .memoize()
        .indefinitely();
```

Without `.memoize().indefinitely()`, each subscriber would trigger a separate `refreshRouteCache()` call, resulting in N database queries for N concurrent requests during a cache refresh. With memoization, the first subscriber triggers the actual refresh, and all subsequent subscribers receive the same result.

The `.onTermination().invoke(() -> inFlightRefresh.set(null))` cleanup is essential. Once the refresh completes (successfully or with a failure), the in-flight reference is cleared so the next cache miss will trigger a new refresh rather than returning stale results.

The `JwksCacheService` uses the identical pattern:

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java, lines 95-101
private Uni<JsonWebKeySet> createFetch(URI jwksUri) {
    return fetchAndCache(jwksUri)
            .onTermination()
            .invoke(() -> inFlightFetches.remove(jwksUri))
            .memoize()
            .indefinitely();
}
```

This prevents the thundering herd problem when JWKS cache entries expire. If 100 requests arrive simultaneously and all need a JWKS refresh, exactly one HTTP call to the identity provider is made. The other 99 requests subscribe to the same memoized `Uni` and receive the result when it arrives.

### How Memoize Works

A normal `Uni` is "cold" -- every subscription triggers a new execution. Think of it as a recipe. `memoize().indefinitely()` converts it to "hot" -- the first subscription executes the recipe, caches the result, and replays it to all subsequent subscribers. The `indefinitely()` variant never expires the cached result (appropriate here because the `Uni` itself has a bounded lifetime, cleared by `onTermination`).

**What a senior might do instead**: use a `CompletableFuture` as a shared promise. The Mutiny approach is more compositional -- `memoize()` fits naturally into a pipeline of operators without requiring you to manage the `CompletableFuture` lifecycle manually. It also inherits Mutiny's context propagation, which matters for OpenTelemetry trace correlation.

### Testing the Coalescing Behavior

The test for this pattern is instructive:

```java
// api/src/test/java/aussie/core/service/ServiceRegistryMultiInstanceTest.java, lines 272-333
@Test
@DisplayName("Concurrent findRouteAsync calls should coalesce into a single refresh")
void concurrentCallsShouldCoalesceIntoSingleRefresh() throws Exception {
    final var findAllCount = new AtomicInteger(0);
    var countingRepo = new CountingRepository(sharedRepository, findAllCount);
    // ...
    // Launch concurrent findRouteAsync calls from multiple threads
    final int threadCount = 5;
    // ...
    // findAll should have been called far fewer times than the thread count
    assertTrue(
            findAllCount.get() <= 2,
            "Expected at most 2 findAll calls from " + threadCount
            + " concurrent requests, got " + findAllCount.get());
}
```

The test launches 5 concurrent threads, all calling `findRouteAsync` after the cache TTL expires. It asserts that `findAll` (the database query) is called at most twice, not five times. The "at most 2" tolerance accounts for a possible race where one thread starts a refresh just before another's `compareAndSet` succeeds. The point is that coalescing prevents 5 requests from turning into 5 database queries.

---

## 7.6 Deadline-Style Timeouts

Timeouts in a reactive system cannot use `Thread.sleep()` or blocking waits. Instead, Mutiny provides `ifNoItem().after(duration).failWith(...)`, which registers a timer on the event loop and fails the `Uni` if no item arrives before the deadline.

### The Pattern

The `RedisTimeoutHelper` centralizes timeout handling for all Redis operations:

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisTimeoutHelper.java, lines 69-75
public <T> Uni<T> withTimeout(Uni<T> operation, String operationName) {
    return operation.ifNoItem().after(timeout).failWith(() -> {
        LOG.warnv("Redis operation timeout: {0} in {1} after {2}", operationName, repositoryName, timeout);
        recordTimeout(operationName);
        return new RedisTimeoutException(operationName, repositoryName);
    });
}
```

This does not block any thread. The Vert.x event loop registers a timer. If the Redis operation completes before the timer fires, the timer is cancelled. If the timer fires first, the `Uni` is failed with a `RedisTimeoutException`.

### Timeout Strategies by Criticality

The `RedisTimeoutHelper` provides four timeout strategies, each appropriate for a different level of operation criticality:

**Fail-fast** (`withTimeout`): Propagates the timeout as an exception. Used for critical operations like session management where a timeout is a genuine error that the caller must handle.

**Fail-soft** (`withTimeoutGraceful`): Returns `Optional.empty()` on timeout. Used for cache reads where a timeout should be treated as a cache miss:

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisTimeoutHelper.java, lines 89-109
public <T> Uni<Optional<T>> withTimeoutGraceful(Uni<T> operation, String operationName) {
    return operation
            .map(Optional::ofNullable)
            .ifNoItem()
            .after(timeout)
            .recoverWithItem(() -> {
                LOG.warnv(
                        "Redis operation timeout (graceful): {0} in {1} after {2}",
                        operationName, repositoryName, timeout);
                recordTimeout(operationName);
                return Optional.empty();
            })
            .onFailure()
            .recoverWithItem(error -> {
                LOG.warnv(
                        "Redis operation failure (graceful): {0} in {1}: {2}",
                        operationName, repositoryName, error.getMessage());
                recordFailure(operationName);
                return Optional.empty();
            });
}
```

**Fail-open** (`withTimeoutFallback`): Returns a custom fallback value. Used for rate limiting where a Redis failure should allow the request through rather than blocking it.

**Fire-and-forget** (`withTimeoutSilent`): Logs and swallows the timeout. Used for cache writes that are not critical to request processing.

### JWKS Fetch Timeout

The `JwksCacheService` applies the same deadline pattern to outbound HTTP calls:

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java, lines 126-153
return webClient
        .getAbs(jwksUri.toString())
        .ssl(jwksUri.getScheme().equals("https"))
        .send()
        .ifNoItem()
        .after(jwksConfig.fetchTimeout())
        .failWith(() -> {
            LOG.warnv("JWKS fetch timeout for {0} after {1}", jwksUri, jwksConfig.fetchTimeout());
            metrics.recordJwksFetchTimeout(jwksUri.getHost());
            return new JwksFetchException("Timeout fetching JWKS from " + jwksUri);
        })
        .map(this::parseResponse)
        .invoke(keySet -> {
            cache.put(jwksUri, new CachedKeySet(keySet, Instant.now().plus(jwksConfig.cacheTtl())));
        })
        .onFailure()
        .recoverWithUni(error -> {
            var stale = cache.getIfPresent(jwksUri);
            if (stale != null) {
                LOG.warnv("Using stale cached JWKS for {0} due to: {1}", jwksUri, error.getMessage());
                return Uni.createFrom().item(stale.keySet());
            }
            return Uni.createFrom().failure(error);
        });
```

Notice the layered resilience: timeout produces a failure, and the failure recovery attempts to use stale cached keys. This means a temporary identity provider outage does not immediately break authentication for every request -- cached keys continue to work until the cache fully expires.

**Why not Thread.sleep?** Beyond the obvious event loop blocking issue, `Thread.sleep` in a reactive context would block the carrier thread, potentially deadlocking the entire application when all event loop threads are sleeping. The `ifNoItem().after()` pattern uses the event loop's built-in timer mechanism, which can track thousands of concurrent deadlines with zero thread overhead.

---

## 7.7 Filter Chain Integration

JAX-RS filters in Quarkus RESTEasy Reactive can return `Uni<Response>` instead of `void`. This integration point is how Aussie implements cross-cutting concerns like rate limiting and access control without blocking the event loop.

### The Contract

A `@ServerRequestFilter` method returning `Uni<Response>` follows a simple protocol:
- Return `Uni<null>` to continue processing (let the request through)
- Return `Uni<Response>` with a non-null response to abort (reject the request)

### RateLimitFilter

The `RateLimitFilter` demonstrates the complete pattern:

```java
// api/src/main/java/aussie/system/filter/RateLimitFilter.java, lines 101-139
@ServerRequestFilter(priority = jakarta.ws.rs.Priorities.AUTHENTICATION - 50)
public Uni<Response> filterRequest(ContainerRequestContext requestContext, HttpServerRequest request) {
    if (!config().enabled()) {
        return Uni.createFrom().nullItem();
    }

    final var path = request.path();
    final var method = request.method().name();
    final var servicePath = ServicePath.parse(path);
    final var serviceId = servicePath.serviceId();
    final var clientId = extractClientId(request);

    final RouteLookupResult routeResult =
            serviceRegistry.findRoute(path, method).orElse(null);

    final EffectiveRateLimit effectiveLimit =
            routeResult != null ? resolveEffectiveLimit(routeResult) : rateLimitResolver.resolvePlatformDefaults();

    final var endpointId =
            routeResult != null ? routeResult.endpoint().map(e -> e.path()).orElse(null) : null;
    final var key = RateLimitKey.http(clientId, serviceId, endpointId);

    return rateLimiter.checkAndConsume(key, effectiveLimit).map(decision -> {
        requestContext.setProperty(RATE_LIMIT_DECISION_ATTR, decision);

        recordMetrics(serviceId, decision);
        setSpanAttributes(decision);

        if (!decision.allowed()) {
            metrics.recordRateLimitExceeded(serviceId, "http");
            dispatchSecurityEvent(decision, serviceId, clientId);
            setExceededSpanAttributes(decision);
            return buildRateLimitResponse(decision);
        }

        // Return null to continue processing
        return null;
    });
}
```

Key observations:

1. **Priority ordering**: `AUTHENTICATION - 50` means this filter runs before authentication. Rate limiting is cheaper than token validation, so rejecting excessive traffic early saves work.

2. **Null return for continuation**: The `map` callback returns `null` (not a `Response`) when the request is allowed. This is how Quarkus signals "continue to the next filter/resource."

3. **Non-blocking rate limit check**: `rateLimiter.checkAndConsume()` returns `Uni<RateLimitDecision>`. If the rate limiter is backed by Redis, this is an async Redis call. The event loop is never blocked waiting for Redis.

4. **HttpServerRequest injection**: The filter injects both `ContainerRequestContext` (JAX-RS abstraction) and `HttpServerRequest` (Vert.x native). The Vert.x request provides access to socket-level details like the remote address, which is not available through the JAX-RS API.

### AccessControlFilter

The `AccessControlFilter` shows a similar pattern with async service lookups:

```java
// api/src/main/java/aussie/system/filter/AccessControlFilter.java, lines 80-106
private Uni<Response> handlePassThroughRequest(
        ContainerRequestContext requestContext, String socketIp, String path, String method) {
    final var servicePath = ServicePath.parse(path);

    if (RESERVED_PATHS.contains(servicePath.serviceId().toLowerCase())) {
        return Uni.createFrom().nullItem();
    }

    // Use reactive chain - no blocking!
    return serviceRegistry.getService(servicePath.serviceId()).flatMap(serviceOpt -> {
        if (serviceOpt.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        var service = serviceOpt.get();
        var routeResult = serviceRegistry.findRoute(servicePath.path(), method);
        if (routeResult.isPresent()
                && routeResult.get().service().serviceId().equals(servicePath.serviceId())) {
            return checkAccessControl(requestContext, socketIp, routeResult.get());
        }

        return checkAccessControl(requestContext, socketIp, new ServiceOnlyMatch(service));
    });
}
```

The comment "Use reactive chain - no blocking!" is not aspirational -- it is a statement of fact. `serviceRegistry.getService()` may hit Redis for a cache lookup, and the `flatMap` ensures the rest of the filter logic runs only after that async operation completes.

**What a senior might do instead**: use a synchronous `@ServerRequestFilter` returning `void` and call `.await().indefinitely()` on the `Uni`. This would block the event loop, defeating the entire purpose of the reactive architecture. The `Uni<Response>` return type is the correct approach because it integrates with the Quarkus event loop scheduler.

---

## 7.8 WebSocket as Full Duplex

WebSocket connections are where reactive programming's advantages are most pronounced. Unlike HTTP request/response, a WebSocket is a long-lived, bidirectional channel. Blocking a thread for the duration of a WebSocket connection (which might be hours) is clearly infeasible.

### Connection Establishment

The `WebSocketGateway.establishProxy()` method shows purely event-driven connection setup:

```java
// api/src/main/java/aussie/adapter/in/websocket/WebSocketGateway.java, lines 148-231
private void establishProxy(RoutingContext ctx, WebSocketUpgradeResult.Authorized auth) {
    final var sessionId = UUID.randomUUID().toString();
    // ...

    // Connect to backend WebSocket FIRST (non-blocking Future)
    vertx.createHttpClient()
            .webSocket(options)
            .onSuccess(backendWs -> {
                // Backend connected - now upgrade client connection (non-blocking)
                ctx.request()
                        .toWebSocket()
                        .onSuccess(clientWs -> {
                            // Both connections established - create proxy session
                            final var session = new WebSocketProxySession(
                                    sessionId, clientWs, backendWs, vertx, config,
                                    authSessionId, userId, messageHandler);
                            activeSessions.put(sessionId, session);
                            // ...

                            // Clean up session when closed
                            clientWs.closeHandler(v -> {
                                activeSessions.remove(sessionId);
                                metrics.decrementActiveWebSockets();
                                // ...
                            });

                            session.start();
                        })
                        .onFailure(err -> {
                            backendWs.close((short) 1001, "Client upgrade failed");
                            ctx.response().setStatusCode(500).end("WebSocket upgrade failed");
                        });
            })
            .onFailure(err -> {
                ctx.response().setStatusCode(502).end("Backend connection failed");
            });
}
```

This code establishes two WebSocket connections (client-to-gateway and gateway-to-backend) using Vert.x Futures, not Mutiny Unis. The distinction matters: the WebSocket layer operates at the Vert.x level, below the JAX-RS abstraction where Mutiny lives. The Futures here are still non-blocking -- they register callbacks that fire when the connection succeeds or fails.

### Bidirectional Message Forwarding

The `WebSocketProxySession.start()` method sets up non-blocking message forwarding:

```java
// api/src/main/java/aussie/core/model/websocket/WebSocketProxySession.java, lines 117-161
public void start() {
    // Set up bidirectional message forwarding with rate limiting (non-blocking)
    clientSocket.handler(buffer -> {
        messageRateLimitHandler
                .checkAndProceed(() -> {
                    resetIdleTimer();
                    backendSocket.write(buffer); // Returns Future, non-blocking
                })
                .subscribe()
                .with(
                        v -> { /* success, message forwarded */ },
                        err -> {
                            rateLimitedMessages.incrementAndGet();
                            closeWithReason((short) 4429, "Message rate limit exceeded");
                        });
    });

    backendSocket.handler(buffer -> {
        resetIdleTimer();
        clientSocket.write(buffer); // Returns Future, non-blocking
    });

    // Handle close from either side
    clientSocket.closeHandler(v -> closeWithReason((short) 1000, "Client disconnected"));
    backendSocket.closeHandler(v -> closeWithReason((short) 1000, "Backend disconnected"));

    // Handle errors
    clientSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Client error: " + t.getMessage()));
    backendSocket.exceptionHandler(t -> closeWithReason((short) 1011, "Backend error: " + t.getMessage()));
    // ...
}
```

Every interaction is event-driven:
- **Message from client**: handler fires, forwards to backend via non-blocking `write()`
- **Message from backend**: handler fires, forwards to client
- **Either side closes**: close handler fires, closes the other side
- **Error on either side**: exception handler fires, closes both sides with an error code

The `closeWithReason` method uses `AtomicBoolean` for idempotency:

```java
// api/src/main/java/aussie/core/model/websocket/WebSocketProxySession.java, lines 214-240
public void closeWithReason(short code, String reason) {
    if (!closing.compareAndSet(false, true)) {
        return; // Already closing
    }
    // Cancel all timers, close both connections
    // ...
}
```

This prevents the cascade where client close triggers backend close, which triggers another close attempt on the already-closing client socket.

### Timer-Based Lifecycle Management

The session uses three timers, all managed through Vert.x's non-blocking timer API:

```java
// api/src/main/java/aussie/core/model/websocket/WebSocketProxySession.java, lines 164-181
private void startIdleTimer() {
    var timeoutMs = config.idleTimeout().toMillis();
    idleTimerId = vertx.setTimer(timeoutMs, id -> closeWithReason((short) 1000, "Idle timeout exceeded"));
}

// ...

private void startMaxLifetimeTimer() {
    var lifetimeMs = config.maxLifetime().toMillis();
    maxLifetimeTimerId =
            vertx.setTimer(lifetimeMs, id -> closeWithReason((short) 1000, "Maximum connection lifetime exceeded"));
}
```

`vertx.setTimer()` registers a one-shot callback on the event loop. No thread is consumed while waiting. This is how Aussie can manage thousands of concurrent WebSocket connections with idle timeouts, maximum lifetimes, and ping/pong health checks -- all without dedicating a single thread per connection.

---

## 7.9 Testing Reactive Code

Testing reactive code requires a different approach than testing synchronous code. The key tool is `.await().atMost(Duration)`, which blocks the test thread (not the event loop) until the `Uni` produces a result or the timeout expires.

### The Basic Pattern

```java
// api/src/test/java/aussie/core/service/GatewayServiceTest.java, lines 132-139
@Test
@DisplayName("Should return RouteNotFound when no matching route exists")
void shouldReturnRouteNotFoundWhenNoMatchingRoute() {
    var request = createRequest("GET", "/api/unknown");

    var result = gatewayService.forward(request).await().indefinitely();

    assertInstanceOf(GatewayResult.RouteNotFound.class, result);
    var routeNotFound = (GatewayResult.RouteNotFound) result;
    assertEquals("/api/unknown", routeNotFound.path());
}
```

`.await().indefinitely()` blocks the JUnit test thread until the `Uni` resolves. This is acceptable in tests because the test thread is not an event loop thread. In production code, calling `.await()` would block the event loop and is never acceptable.

The tests use `.await().atMost(Duration)` with a constant timeout for operations that involve real async work:

```java
// api/src/test/java/aussie/core/service/GatewayServiceTest.java, line 44
private static final Duration TIMEOUT = Duration.ofSeconds(5);

// api/src/test/java/aussie/core/service/GatewayServiceTest.java, line 123
serviceRegistry.register(service).await().atMost(TIMEOUT);
```

The 5-second timeout prevents tests from hanging indefinitely if something goes wrong. If the `Uni` does not resolve within 5 seconds, the test fails with a timeout exception rather than hanging forever.

### Why Not .get()?

`Uni` does not have a `.get()` method. This is intentional. `CompletableFuture.get()` is a blocking call that encourages the wrong patterns. Mutiny forces you to either compose operations reactively (in production code) or explicitly acknowledge that you are blocking for testing purposes (with `.await()`).

### Testing Asynchronous Races

The multi-instance coalescing test demonstrates testing concurrent behavior:

```java
// api/src/test/java/aussie/core/service/ServiceRegistryMultiInstanceTest.java, lines 298-327
final int threadCount = 5;
final var startLatch = new CountDownLatch(1);
final var doneLatch = new CountDownLatch(threadCount);
final var errors = new ArrayList<Throwable>();

for (int i = 0; i < threadCount; i++) {
    new Thread(() -> {
                try {
                    startLatch.await();
                    registry.findRouteAsync("/api/data", "GET")
                            .await()
                            .atMost(TIMEOUT);
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            })
            .start();
}

// Release all threads at once
startLatch.countDown();
doneLatch.await();

assertTrue(errors.isEmpty(), "Unexpected errors: " + errors);
```

The `CountDownLatch` pattern ensures all threads start simultaneously, maximizing the chance of exercising the coalescing logic. Each thread calls `.await().atMost(TIMEOUT)` to get a synchronous result for assertion purposes. The `AtomicInteger` counter on the repository verifies that the database was queried at most twice despite five concurrent callers.

### Test Doubles for Reactive Interfaces

The test file shows how to create test doubles for reactive interfaces:

```java
// api/src/test/java/aussie/core/service/GatewayServiceTest.java, lines 289-316
private static class TestProxyClient implements ProxyClient {
    private ProxyResponse response = new ProxyResponse(200, Map.of(), new byte[0]);
    private Throwable error = null;
    private PreparedProxyRequest lastRequest = null;

    void setResponse(ProxyResponse response) {
        this.response = response;
        this.error = null;
    }

    void setError(Throwable error) {
        this.error = error;
        this.response = null;
    }

    @Override
    public Uni<ProxyResponse> forward(PreparedProxyRequest request) {
        this.lastRequest = request;
        if (error != null) {
            return Uni.createFrom().failure(error);
        }
        return Uni.createFrom().item(response);
    }
}
```

The test double wraps synchronous values in `Uni.createFrom().item()` or `Uni.createFrom().failure()`. This is a common pattern: test doubles for reactive interfaces return immediately-resolved Unis, which simplifies test setup while still exercising the reactive composition logic in the code under test.

---

## 7.10 The Virtual Threads Alternative

Java 21 introduces virtual threads (Project Loom), which fundamentally change the calculus around reactive programming. Virtual threads are cheap -- you can create millions of them. They block without consuming an OS thread, because the JVM unmounts the virtual thread from its carrier thread when it encounters a blocking operation and remounts it when the operation completes.

### What Virtual Threads Would Simplify

With virtual threads, the `GatewayService.forward()` method could be written imperatively:

```java
// Hypothetical virtual threads version (not actual code)
public GatewayResult forward(GatewayRequest request) {
    long startTime = System.nanoTime();

    var routeResult = serviceRegistry.findRoute(request.path(), request.method());
    if (routeResult.isEmpty()) {
        return new GatewayResult.RouteNotFound(request.path());
    }

    var routeMatch = (RouteMatch) routeResult.get();
    var authResult = routeAuthService.authenticate(request, routeMatch);

    return switch (authResult) {
        case Authenticated auth -> forwardWithToken(request, routeMatch, auth.token());
        case NotRequired nr -> forwardWithoutToken(request, routeMatch);
        case Unauthorized u -> new GatewayResult.Unauthorized(u.reason());
        // ...
    };
}
```

No `flatMap`, no `map`, no `Uni<T>` at all. The code reads like synchronous Java, and the JVM handles the non-blocking mechanics transparently. Every blocking call (database lookup, HTTP request to upstream, Redis check) would transparently yield the virtual thread without consuming an OS thread.

### When Reactive Is Still Justified

Despite virtual threads, reactive programming retains advantages in specific scenarios that are present in this codebase:

**Request coalescing and memoization** (Sections 7.4-7.5): The `deferred + memoize + compareAndSet` pattern in `ServiceRegistry.ensureCacheFresh()` is inherently about shared computation across subscribers. Virtual threads do not provide a built-in mechanism for "if N threads all need the same result, compute it once." You would need to build the equivalent using `ConcurrentHashMap<Key, CompletableFuture<Value>>`, which is essentially reinventing the memoize pattern.

**Streaming with backpressure** (the `Multi<T>` cases): Virtual threads do not address backpressure. If a producer generates items faster than a consumer can process them, you still need a reactive stream to signal the producer to slow down.

**Event-driven WebSocket management**: The WebSocket proxy session uses Vert.x's event loop model directly. Converting this to virtual threads would mean one virtual thread per WebSocket connection, blocking on reads. While feasible, it loses the elegance of the handler-based model where a single event loop processes events across thousands of connections.

**Composition and error handling**: The `onFailure().recoverWithItem()` and `onFailure().recoverWithUni()` patterns provide a declarative approach to error handling that is arguably more explicit than try/catch blocks. In the reactive model, error handling is part of the pipeline definition, not an afterthought.

### The Pragmatic View

Quarkus supports running on virtual threads via the `@RunOnVirtualThread` annotation. A pragmatic migration strategy would be:

1. Keep the core ports returning `Uni<T>` -- they define the contract, and `Uni` is a better abstraction than `CompletableFuture` regardless of threading model.
2. Use virtual threads for new endpoints that do not need coalescing, memoization, or streaming.
3. Keep reactive pipelines where the composition patterns (deferred, memoize, Multi) provide genuine value.

The decision is not binary. Virtual threads and reactive programming solve overlapping but not identical problems. Virtual threads eliminate the need for reactive code in the simple "async I/O" case. Reactive programming remains the right choice when you need to express complex data flow patterns -- shared computation, backpressure, fan-out/fan-in -- that virtual threads do not inherently address.

**Trade-off**: The biggest cost of the reactive approach is readability. `flatMap` chains are harder to follow than imperative code. Stack traces in reactive code are notoriously unhelpful because the actual execution is spread across event loop callbacks. Debugging requires understanding how subscriptions propagate. For a gateway where the reactive patterns provide tangible benefits (coalescing, non-blocking WebSocket management, deadline-based timeouts), this cost is justified. For a typical CRUD service, virtual threads would be the simpler and equally performant choice.
