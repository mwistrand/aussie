# AGENTS.md

This file provides guidance to automated coding agents when working with code in this repository.

## Build & Run Commands

- **Build:** `./gradlew build`
- **Dev mode (live reload):** `./gradlew quarkusDev` (Dev UI at http://localhost:8080/q/dev/)
- **Run tests:** `./gradlew test`
- **Single test class:** `./gradlew test --tests "aussie.AdminResourceTest"`
- **Single test method:** `./gradlew test --tests "aussie.AdminResourceTest.testMethodName"`
- **Native build:** `./gradlew build -Dquarkus.native.enabled=true`
- **Uber-jar:** `./gradlew build -Dquarkus.package.jar.type=uber-jar`
- **Coverage report:** `make coverage` (generates JaCoCo HTML + CLI coverage)
- **Run benchmarks:** `cd api && ./gradlew jmh` (JMH results in `api/build/results/jmh/`)
- **Single benchmark:** `cd api && ./gradlew jmh -Pjmh.includes='RouteMatchingBenchmark'`

## Architecture

This is a Quarkus REST application using Gradle. Key dependencies:
- **quarkus-arc** - CDI dependency injection
- **quarkus-rest** - JAX-RS REST endpoints
- **quarkus-junit5** + **rest-assured** - Testing

Project layout:
- `src/main/java/aussie/` - Application code (REST resources)
- `src/test/java/aussie/` - Unit/integration tests with `@QuarkusTest`
- `src/jmh/java/aussie/benchmark/` - JMH performance benchmarks
- `src/native-test/java/aussie/` - Native image integration tests

## Code Style

- Java 21
- 4-space indent, braces on same line
- No wildcard imports; group: java.*, jakarta.*, third-party, project
- Prefer imports to fully-resolved class or object names.
- PascalCase for classes, camelCase for methods/variables, UPPER_SNAKE_CASE for constants
- Prefer `var` over explicit types, except when explicit types are required for understandability
- Use `final` for variables that will not be reassigned.
- Use `sealed` for interfaces where it makes sense.
- Never block threads unless absolutely required, instead preferring reactive interfaces
- Always use `@Override`
- Prefer `Optional` over null for API return types
- Always use constructor injection

## Testing

- For Java tests, always use JUnit5 assertions

## Benchmarks

- JMH benchmarks live in `src/jmh/java/aussie/benchmark/`
- When adding new hot-path logic (request filters, routing, caching, rate limiting), add a corresponding JMH benchmark
- Benchmarks should target pure domain logic that does not require a running Quarkus container
- Use `@State(Scope.Thread)` for mutable state, `@State(Scope.Benchmark)` for read-only shared fixtures
- Use `Blackhole.consume()` for results and `@Param` for scaling benchmarks

## Database

- Cassandra and Redis by default
- Always include migration scripts when necessary

## Documentation

All documentation should be added to the top-level docs/ directory:
- Documentation for platform teams running Aussie is at docs/platform
- Documentation for API teams is at docs/api

Changes may also require updates to the guidebook in `guidebook/`.
