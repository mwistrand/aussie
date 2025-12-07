# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

- **Build:** `./gradlew build`
- **Dev mode (live reload):** `./gradlew quarkusDev` (Dev UI at http://localhost:8080/q/dev/)
- **Run tests:** `./gradlew test`
- **Single test class:** `./gradlew test --tests "aussie.AdminResourceTest"`
- **Single test method:** `./gradlew test --tests "aussie.AdminResourceTest.testMethodName"`
- **Native build:** `./gradlew build -Dquarkus.native.enabled=true`
- **Uber-jar:** `./gradlew build -Dquarkus.package.jar.type=uber-jar`

## Architecture

This is a Quarkus REST application using Gradle. Key dependencies:
- **quarkus-arc** - CDI dependency injection
- **quarkus-rest** - JAX-RS REST endpoints
- **quarkus-junit5** + **rest-assured** - Testing

Project layout:
- `src/main/java/aussie/` - Application code (REST resources)
- `src/test/java/aussie/` - Unit/integration tests with `@QuarkusTest`
- `src/native-test/java/aussie/` - Native image integration tests

## Code Style

- Java 21
- 4-space indent, braces on same line
- No wildcard imports; group: java.*, jakarta.*, third-party, project
- PascalCase for classes, camelCase for methods/variables, UPPER_SNAKE_CASE for constants
- Prefer `var` over explicit types, except when explicit types are required for understandability
- Always use `@Override`
- Prefer `Optional` over null for API return types
- Use `var` only for local variables with obvious types

## Database

- Cassandra and Redis by default
- Always include migration scripts when necessary
