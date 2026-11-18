#!/usr/bin/env python3
"""Reject mutable third-party GitHub Action references."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
ACTION = re.compile(r"^\s*(?:-\s*)?uses:\s*(?P<ref>[^\s#]+)")
SHA = re.compile(r"^[0-9a-f]{40}$")


def mutable_action_refs(workflow_dir: Path = WORKFLOWS) -> list[str]:
    violations = []
    workflows = [*workflow_dir.glob("*.yml"), *workflow_dir.glob("*.yaml")]
    for workflow in sorted(workflows):
        for line_number, line in enumerate(workflow.read_text().splitlines(), 1):
            match = ACTION.match(line)
            if match is None or match.group("ref").startswith("./"):
                continue
            action, separator, ref = match.group("ref").rpartition("@")
            if not separator or not SHA.fullmatch(ref):
                path = workflow.relative_to(ROOT) if workflow.is_relative_to(ROOT) else workflow
                violations.append(f"{path}:{line_number}: {action}@{ref}")
    return violations


def main() -> int:
    violations = mutable_action_refs()
    if violations:
        print("Mutable GitHub Action references found:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1
    print("GitHub Actions are pinned to immutable commit SHAs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
