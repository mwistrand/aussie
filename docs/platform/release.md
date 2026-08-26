# Release evidence

Releases are tag-driven. Pushing a protected `v*` tag runs the reusable CI
workflow first; the release job stops if formatting, Java tests, Go checks, or
the demo build fails.

The `production-release` environment must require reviewer approval and be
restricted to protected `v*` tags. Gradle dependency locks and SHA-256
verification metadata are enforced by CI; Go and npm dependency locks are
also verified on every change.

The successful job publishes the JVM image to GHCR, emits BuildKit provenance
and an SBOM, signs the image with keyless Cosign, attaches a CycloneDX SBOM
attestation, verifies both signatures, and uploads an evidence manifest with the
image digest, SBOM checksum, compatibility note, and rollback instructions.

Verify a published image with:

```sh
cosign verify ghcr.io/mwistrand/aussie:vX.Y.Z \\
  --certificate-identity-regexp 'https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/vX.Y.Z' \\
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Rollback by deploying the previous verified tag. Database changes require the
migration guide and a forward-fix; do not roll back an image across an
irreversible schema change without restoring the documented compatible schema.

Before publishing, attach the migration/compatibility note to the release
evidence and record the previous verified image tag. During rollback, verify
the image signature and provenance, deploy that tag, and run the packaged E2E
smoke plus readiness checks before restoring traffic.

The hosted rollback rehearsal is the protected manual
`.github/workflows/rollback.yml` gate. Configure the `production-rollback`
environment with the `PRODUCTION_KUBECONFIG` secret, then provide the candidate
and rollback tags, deployment coordinates, public base URL, and smoke path. The
workflow verifies both release signatures, deploys the candidate, checks rollout
and health traffic, restores the rollback tag, and repeats those checks. A
successful hosted run is required before the plan's rollback checklist item is
closed.

The uploaded `*.evidence.json` manifest is the release handoff. Verify its
`digest` against the registry image and its `sbom_sha256` against the uploaded
CycloneDX file before deployment.
