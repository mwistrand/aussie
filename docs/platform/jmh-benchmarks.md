# JMH Microbenchmarks

This document covers the [JMH](https://github.com/openjdk/jmh) microbenchmark suite used to guard Aussie's hot-path domain logic against performance regressions. It is distinct from the `aussie benchmark` CLI tool (see [Benchmarking in the platform README](README.md#benchmarking)), which measures end-to-end latency through a running gateway.

## What is benchmarked

JMH benchmarks live in `api/src/jmh/java/aussie/benchmark/` and exercise pure domain logic that does not require a running Quarkus container:

| Benchmark                    | Target                                                                 |
|------------------------------|------------------------------------------------------------------------|
| `RouteMatchingBenchmark`     | `ServiceRegistration#findRoute` (exact, parameterized, wildcard, rewrite) and `ServiceRegistry`-style resolve-and-match through the per-service `RouteIndex` across 1/10/100/500 services |
| `BucketAlgorithmBenchmark`   | Token bucket allowed/rejected/refill and status computation            |
| `BloomFilterBenchmark`       | Revocation bloom filter "definitely not revoked" fast path             |
| `RevocationCacheBenchmark`   | Caffeine-backed revocation cache hit, miss, and expiry detection       |
| `CidrMatchingBenchmark`      | Trusted proxy CIDR matching and access control evaluation              |
| `LocalCacheBenchmark`        | `CaffeineLocalCache` get/put with TTL jitter, concurrent throughput    |
| `CorsMatchingBenchmark`      | CORS origin and method matching                                        |
| `HeaderPipelineBenchmark`    | `ProxyRequestPreparer.prepare` and `filterResponseHeaders` across 10/20/40 headers, with and without a `Connection` header |
| `TokenValidationBenchmark`   | `OidcTokenValidator.validate` cache-hit cost, with and without a configured audience |

## Running benchmarks

All JMH tasks run from the `api/` directory.

```bash
# Run the full suite
cd api && ./gradlew jmh

# Run a single benchmark class
cd api && ./gradlew jmh -Pjmh.includes='RouteMatchingBenchmark'

# Run a specific method (regex supported)
cd api && ./gradlew jmh -Pjmh.includes='BucketAlgorithm.*allowed'
```

Results are written to `api/build/results/jmh/` in JSON format. A full run takes several minutes.

## Configuration

The suite is configured in `api/build.gradle` via the `me.champeau.jmh` plugin. Defaults:

| Setting           | Default | Purpose                                        |
|-------------------|---------|------------------------------------------------|
| `fork`            | `2`     | JVM forks per benchmark (isolates JIT state)   |
| `warmupIterations`| `5`     | Warmup iterations before measurement starts   |
| `iterations`      | `5`     | Measurement iterations                         |
| `jmhVersion`      | `1.37`  | JMH version                                    |
| `failOnError`     | `true`  | Abort the run if any benchmark throws          |
| `resultFormat`    | `JSON`  | Output format written under `build/results/jmh/` |

Override at invocation time with Gradle properties, for example:

```bash
cd api && ./gradlew jmh -Pjmh.fork=1 -Pjmh.iterations=3
```

## Interpreting results

Each benchmark reports a central tendency and confidence interval. Focus on the `Score` column and the `±` error value. A regression is meaningful when the new score is outside the prior run's confidence interval by more than roughly 10%.

Benchmarks declared with `@BenchmarkMode(Mode.AverageTime)` and `@OutputTimeUnit(TimeUnit.NANOSECONDS)` report nanoseconds per operation (lower is better). The concurrent throughput benchmark in `LocalCacheBenchmark` reports operations per second (higher is better).

When comparing runs, always compare on the same hardware and with the same JVM version. Baselines are not checked into the repository; capture them locally before a refactor and diff against the post-change JSON.

## When to add a benchmark

Add a JMH benchmark when introducing or modifying logic that executes on every request, or on a high fraction of requests. Examples include request filters, routing, caching, rate limiting, and token validation fast paths.

Do not add benchmarks for logic that only runs at configuration time (admin operations, service registration) or logic that delegates to a third-party library for its cost (JWT signature verification, Cassandra or Redis round trips). In those cases the benchmark would measure the library, not your code.

Follow the conventions in `CLAUDE.md`: use `@State(Scope.Thread)` for mutable state, `@State(Scope.Benchmark)` for read-only shared fixtures, `Blackhole.consume()` for results, and `@Param` for scaling benchmarks.
