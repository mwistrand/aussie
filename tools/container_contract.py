#!/usr/bin/env python3
"""Reject mutable container inputs in the release build and CI services."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RELEASE_INPUTS = (
    ROOT / "api/src/main/docker/Dockerfile.jvm",
    ROOT / "api/src/main/docker/Dockerfile.e2e",
    ROOT / "api/src/e2eTest/java/aussie/e2e/support/E2EHarness.java",
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/security.yml",
)
SYNTAX = re.compile(r"^\s*#\s*syntax\s*=\s*(?P<image>[^\s]+)", re.IGNORECASE)
FROM = re.compile(r"^\s*FROM\s+(?P<image>[^\s]+)", re.IGNORECASE)
SERVICE_IMAGE = re.compile(r"^\s+image:\s+(?P<image>[^\s#]+)")
SCANNER_IMAGE = re.compile(r"^\s+TRIVY_IMAGE:\s+(?P<image>[^\s#]+)")
JAVA_CONTAINER_CONSTANT = re.compile(
    r"private\s+static\s+final\s+String\s+(?P<name>[A-Z][A-Z0-9_]*_IMAGE)"
    r"\s*=\s*\"(?P<image>[^\"]+)\""
)
JAVA_CONTAINER_IMAGE = re.compile(r'DockerImageName\.parse\(\s*"(?P<image>[^"]+)"\)')
DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")


def mutable_image_refs(paths: tuple[Path, ...] = RELEASE_INPUTS) -> list[str]:
    violations = []
    for path in paths:
        if path.name.startswith("Dockerfile"):
            patterns = (SYNTAX, FROM)
        elif path.suffix == ".java":
            patterns = (JAVA_CONTAINER_IMAGE,)
        else:
            patterns = (SERVICE_IMAGE, SCANNER_IMAGE)
        source = path.read_text()
        matches = []
        if path.suffix == ".java":
            matches.extend(
                (source.count("\n", 0, match.start()) + 1, match)
                for match in JAVA_CONTAINER_CONSTANT.finditer(source)
            )
        for line_number, line in enumerate(source.splitlines(), 1):
            matches.extend(
                (line_number, match)
                for pattern in patterns
                for match in (pattern.search(line),)
                if match is not None
            )
        for line_number, match in matches:
            if not DIGEST.search(match.group("image")):
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
