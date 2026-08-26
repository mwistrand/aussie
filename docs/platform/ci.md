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

The packaged-artifact E2E gate runs on pull requests, pushes to `main`, and
protected release workflow calls. It covers authentication-boundary rejection,
protected routing, migrations, administrative concurrency, restart behavior,
and observability. A failed or skipped E2E job is not release evidence; the
native artifact build runs in the same reusable workflow. A scheduled
`Resilience` workflow repeats the packaged lifecycle suite and uploads container
logs on failure; run it manually with `workflow_dispatch` when validating a
change under a larger repeat count. Hosted rollback remains a separate,
deployment-owned gate and is not claimed by this repository-only workflow.
