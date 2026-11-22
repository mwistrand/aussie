#!/usr/bin/env python3
"""Check the release workflow's reproducible CLI artifact contract."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/release.yml"

REQUIRED_SNIPPETS = (
    ("source timestamp", 'source_date_epoch="$(git show -s --format=%ct "$RELEASE_COMMIT")"'),
    ("reproducible Go build", "go build -trimpath -buildvcs=false"),
    ("normalized archive timestamps", 'touch -d "@$source_date_epoch" "$name" "$binary"'),
    ("normalized tar ownership", "--owner=0 --group=0 --numeric-owner"),
    ("normalized zip metadata", "zip -q -X -r"),
    ("CLI checksums", 'sha256sum *.tar.gz *.zip > "aussie-${RELEASE_VERSION}.sha256"'),
    ("CLI signatures", "cosign sign-blob --yes"),
    ("CLI signature verification", "cosign verify-blob"),
    ("CLI provenance subjects", "subject-path: |\n            dist/*.tar.gz\n            dist/*.zip"),
    ("CLI SBOM checksum", 'sha256sum "aussie-cli-${RELEASE_VERSION}.cdx.json"'),
    ("CLI SBOM signing target", 'sbom="aussie-cli-${RELEASE_VERSION}.cdx.json"'),
    ("CLI SBOM signature output", '--output-signature "$sbom.sig"'),
    ("CLI SBOM certificate output", '--output-certificate "$sbom.pem"'),
    ("CLI SBOM verification signature", '--signature "$sbom.sig"'),
    ("CLI SBOM verification certificate", '--certificate "$sbom.pem"'),
    (
        "CLI SBOM upload",
        'dist/*.sha256 \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.sha256"',
    ),
    (
        "CLI SBOM checksum upload",
        '"aussie-cli-${RELEASE_VERSION}.cdx.json" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.sha256" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.sig"',
    ),
    (
        "CLI SBOM signature upload",
        '"aussie-cli-${RELEASE_VERSION}.cdx.json.sha256" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.sig" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.pem"',
    ),
    (
        "CLI SBOM certificate upload",
        '"aussie-cli-${RELEASE_VERSION}.cdx.json.sig" \\\n            "aussie-cli-${RELEASE_VERSION}.cdx.json.pem" \\\n            --clobber',
    ),
    ("source SBOM signing", 'sbom="aussie-${RELEASE_VERSION}.cdx.json"'),
    ("source SBOM signature verification", '--signature "${sbom}.sig"'),
    ("source SBOM evidence signature", "sbom_signature: $sbomSignature"),
    ("source SBOM evidence certificate", "sbom_certificate: $sbomCertificate"),
    (
        "source SBOM evidence uploads",
        "aussie-${{ github.ref_name }}.cdx.json.pem\n            aussie-${{ github.ref_name }}.evidence.json",
    ),
)


def violations(workflow: Path = WORKFLOW) -> list[str]:
    source = workflow.read_text()
    label = workflow.relative_to(ROOT) if workflow.is_relative_to(ROOT) else workflow
    return [f"{label}: missing {description}" for description, snippet in REQUIRED_SNIPPETS if snippet not in source]


def main() -> int:
    problems = violations()
    if problems:
        print("Release workflow contract violations found:", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1
    print("Release workflow preserves reproducible, signed CLI artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
