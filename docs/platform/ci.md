# CI gates

Every pull request runs the Java, CLI, and demo checks in `.github/workflows/ci.yml`.
The Java job provisions pinned Redis and Cassandra service containers, so tests
that discover those providers never depend on developer-local ports. The packaged-
artifact E2E job runs on pushes to `main` because it builds and starts multiple
containers.

The Java `check` task also enforces the JaCoCo floor: at least 80% instruction
coverage and 70% branch coverage. A change that lowers either metric fails the
pull-request gate.

The order is intentional: formatting and tests pass before any image is built.
The JVM Dockerfile uses `spotlessCheck`, never rewrites source files, and its
image build remains separate from the CI test gate.

E2E remains a separate gate until the suite covers all trust boundaries listed
in the production-readiness roadmap. A failed or skipped E2E job is not release
evidence.
