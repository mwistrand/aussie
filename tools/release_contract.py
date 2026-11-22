#!/usr/bin/env python3
"""Validate the release evidence manifest against its local SBOM."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


COMMIT = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
REQUIRED_FIELDS = {
    "version",
    "commit",
    "image",
    "digest",
    "sbom",
    "sbom_sha256",
    "sbom_signature",
    "sbom_certificate",
}


def validate_evidence(evidence_path: Path, root: Path | None = None) -> list[str]:
    root = (root or evidence_path.parent).resolve()
    try:
        evidence = json.loads(evidence_path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        return [f"unable to read evidence manifest: {error}"]

    if not isinstance(evidence, dict):
        return ["evidence manifest must be a JSON object"]
    errors: list[str] = []
    missing = sorted(REQUIRED_FIELDS - evidence.keys())
    if missing:
        return [f"missing evidence fields: {', '.join(missing)}"]

    for field in (
        "version",
        "image",
        "sbom",
        "sbom_sha256",
        "sbom_signature",
        "sbom_certificate",
    ):
        if not isinstance(evidence[field], str) or not evidence[field].strip():
            errors.append(f"{field} must be a non-empty string")
    if not isinstance(evidence["commit"], str) or COMMIT.fullmatch(evidence["commit"]) is None:
        errors.append("commit must be a 40-character hexadecimal SHA")
    if not isinstance(evidence["digest"], str) or DIGEST.fullmatch(evidence["digest"]) is None:
        errors.append("digest must be a sha256 image digest")
    if isinstance(evidence["sbom_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", evidence["sbom_sha256"]) is None:
        errors.append("sbom_sha256 must be a 64-character hexadecimal digest")

    if isinstance(evidence["sbom"], str):
        sbom = (root / evidence["sbom"]).resolve()
        if not sbom.is_relative_to(root):
            errors.append("sbom must remain within the evidence root")
        elif not sbom.is_file():
            errors.append(f"sbom does not exist: {evidence['sbom']}")
        elif isinstance(evidence["sbom_sha256"], str):
            actual = hashlib.sha256(sbom.read_bytes()).hexdigest()
            if actual != evidence["sbom_sha256"]:
                errors.append("sbom_sha256 does not match the referenced SBOM")

    for field in ("sbom_signature", "sbom_certificate"):
        if not isinstance(evidence[field], str):
            continue
        artifact = (root / evidence[field]).resolve()
        if not artifact.is_relative_to(root):
            errors.append(f"{field} must remain within the evidence root")
        elif not artifact.is_file():
            errors.append(f"{field} does not exist: {evidence[field]}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--root", type=Path)
    args = parser.parse_args()
    errors = validate_evidence(args.evidence, args.root)
    if errors:
        print("Release evidence contract violations found:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Release evidence manifest is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
