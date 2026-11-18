#!/usr/bin/env python3
"""Reject mutable container inputs in the release build and CI services."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RELEASE_INPUTS = (
    ROOT / "api/src/main/docker/Dockerfile.jvm",
    ROOT / ".github/workflows/ci.yml",
)
SYNTAX = re.compile(r"^\s*#\s*syntax\s*=\s*(?P<image>[^\s]+)", re.IGNORECASE)
FROM = re.compile(r"^\s*FROM\s+(?P<image>[^\s]+)", re.IGNORECASE)
SERVICE_IMAGE = re.compile(r"^\s+image:\s+(?P<image>[^\s#]+)")
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")


def mutable_image_refs(paths: tuple[Path, ...] = RELEASE_INPUTS) -> list[str]:
    violations = []
    for path in paths:
        patterns = (SYNTAX, FROM) if path.name.startswith("Dockerfile") else (SERVICE_IMAGE,)
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            match = next((pattern.match(line) for pattern in patterns if pattern.match(line)), None)
            if match is not None and not DIGEST.search(match.group("image")):
                relative = path.relative_to(ROOT) if path.is_relative_to(ROOT) else path
                violations.append(f"{relative}:{line_number}: {match.group('image')}")
    return violations


def main() -> int:
    violations = mutable_image_refs()
    if violations:
        print("Mutable release container references found:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("Release container inputs are pinned to immutable digests")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
