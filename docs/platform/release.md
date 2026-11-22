# Release evidence

Releases are tag-driven. Pushing a protected `v*` tag runs the reusable CI
workflow first; the release job stops if formatting, Java tests, Go checks, or
the demo build fails.

The `production-release` environment must require reviewer approval and be
restricted to protected `v*` tags. Gradle dependency locks and SHA-256
verification metadata are enforced by CI; Go and npm dependency locks are
also verified on every change.

The successful job publishes the JVM image to GHCR, emits BuildKit provenance
and an SBOM, signs the image and source SBOM with keyless Cosign, attaches a
CycloneDX SBOM attestation, verifies the signatures, and uploads an evidence
manifest with the image digest, SBOM checksum, signed-SBOM files, compatibility
note, and rollback instructions.
It also tests and cross-builds the CLI for Linux and macOS on amd64 and arm64,
plus Windows amd64. Each archive has a SHA-256 checksum, a keyless Cosign blob
signature, and GitHub build provenance; the CLI dependency SBOM has its own
checksum and keyless Cosign signature. The workflow creates or updates the
matching GitHub Release and attaches these files as durable release assets.

Verify a published image with:

```sh
cosign verify ghcr.io/mwistrand/aussie:vX.Y.Z \
  --certificate-identity 'https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/vX.Y.Z' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Before applying the digest, verify the signed SBOM and build provenance as
well. Use the exact digest recorded in the release evidence manifest; do not
verify only the mutable tag:

```sh
IMAGE=ghcr.io/mwistrand/aussie
DIGEST=sha256:<digest-from-evidence>
IDENTITY=https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/vX.Y.Z

cosign verify-attestation --type cyclonedx \
  --certificate-identity "$IDENTITY" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  "$IMAGE@$DIGEST"
gh attestation verify "oci://$IMAGE@$DIGEST" \
  --repo mwistrand/aussie \
  --signer-workflow mwistrand/aussie/.github/workflows/release.yml \
  --source-ref "refs/tags/vX.Y.Z" \
  --bundle-from-oci
trivy image --severity HIGH,CRITICAL --exit-code 1 "$IMAGE@$DIGEST"
```

Apply the same verified digest from the evidence manifest in the deployment
overlay. A tag-only deployment or a digest that was not scanned and attested
by the release workflow is not a supported production rollout.

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
`digest` against the registry image, its `sbom_sha256` against the uploaded
CycloneDX file, and the `sbom_signature`/`sbom_certificate` files with Cosign
before deployment.

## Revocation and rollback

If an image, CLI archive, signing workflow, base image, dependency, or signing
key is compromised, stop promotion, revoke the affected artifact or key in the
registry/identity system, and remove the release from distribution. Record the
affected digest, tag, advisory, and evidence manifest; publish a replacement
release from a reviewed commit; and rerun signature, SBOM, vulnerability, and
qualification gates before restoring promotion. Treat a database migration as
forward-only during this response: restore from the documented backup or ship
a compatible forward fix instead of rolling the application back across an
irreversible schema change.

The release workflow then runs the repository-owned Kubernetes qualification
against that exact signed image digest. The release is not complete unless the
qualification job passes; its Markdown report is uploaded as the release
qualification artifact alongside the evidence manifest.

Verify a CLI archive before installation with:

```sh
sha256sum --check aussie-vX.Y.Z.sha256
cosign verify-blob \
  --signature aussie-vX.Y.Z-linux-amd64.tar.gz.sig \
  --certificate aussie-vX.Y.Z-linux-amd64.tar.gz.pem \
  --certificate-identity 'https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/vX.Y.Z' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  aussie-vX.Y.Z-linux-amd64.tar.gz
```

Verify the published CLI SBOM before using it as release evidence:

```sh
sha256sum --check aussie-cli-vX.Y.Z.cdx.json.sha256
cosign verify-blob \
  --signature aussie-cli-vX.Y.Z.cdx.json.sig \
  --certificate aussie-cli-vX.Y.Z.cdx.json.pem \
  --certificate-identity 'https://github.com/mwistrand/aussie/.github/workflows/release.yml@refs/tags/vX.Y.Z' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  aussie-cli-vX.Y.Z.cdx.json
```
