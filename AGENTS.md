**AGENTS.md — Repo guidelines for automated agents

- **Build / Run**
  - Build: `./gradlew build`
  - Dev mode (live coding): `./gradlew quarkusDev`
  - Native build: `./gradlew build -Dquarkus.native.enabled=true`
  - Package uber-jar: `./gradlew build -Dquarkus.package.jar.type=uber-jar`

- **Test / Lint**
  - All tests: `./gradlew test`
  - Single test class: `./gradlew test --tests "aussie.AdminResourceTest"`
  - Single test method: `./gradlew test --tests "aussie.AdminResourceTest.testMethodName"`
  - Quick health check: `./gradlew check` (runs basic verification tasks)

- **Code style / conventions**
  - Java version: target Java 21 (see `build.gradle:22`).
  - Formatting: follow standard Java formatting (4-space indent, brace on same line). Use a formatter (e.g., Google Java Format or Spotless) when available.
  - Imports: no wildcard imports; group and sort imports (java.*, javax.*, third-party, project); remove unused imports.
  - Naming: classes/interfaces PascalCase, methods/variables camelCase, constants UPPER_SNAKE_CASE, packages lower.case.
  - Types: prefer explicit types for public APIs; use var only for local variables with obvious types.
  - Annotations: always use `@Override` where appropriate.
  - Error handling: don't swallow exceptions; log with context and rethrow or wrap in a meaningful exception; prefer specific exceptions over generic `Exception`.
  - Nulls: avoid returning null where Optional is appropriate for API-level code.

- **Testing & API**
  - Use JUnit5 and RestAssured for tests (configured in `build.gradle:15-16`).
  - Keep unit tests fast and deterministic; integration tests may live under `src/native-test/` per project layout.

- **Repo rules**
  - No `.cursor` or Copilot instructions were found in the repo. If added, include their path here for agents to respect.

Keep changes minimal and focused; when in doubt prefer small, reviewable commits and call out required infra changes in the PR description.
