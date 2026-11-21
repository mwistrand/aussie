#!/usr/bin/env python3
"""Check that Markdown links to repository files still resolve."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
REFERENCE_DEFINITION = re.compile(r"^[ \t]{0,3}\[[^\]]+\]:[ \t]*(<[^>\n]+>|[^\s]+)")
FENCE = re.compile(r"^[ ]{0,3}(?P<marker>`{3,}|~{3,})")
INLINE_CODE = re.compile(r"(`+)(?:(?!`).)*?\1")
IGNORED_DIRECTORIES = {".git", "build", "node_modules", "dist"}


def markdown_files(root: Path = ROOT) -> list[Path]:
    root = root.resolve()
    return sorted(
        path
        for path in root.rglob("*.md")
        if not IGNORED_DIRECTORIES.intersection(path.relative_to(root).parts)
    )


def _without_code(lines: list[str]) -> list[str]:
    sanitized = []
    fence: tuple[str, int] | None = None
    for line in lines:
        match = FENCE.match(line)
        if fence is not None:
            if match is not None:
                marker = match.group("marker")
                if marker[0] == fence[0] and len(marker) >= fence[1]:
                    fence = None
            sanitized.append("")
        elif match is not None:
            marker = match.group("marker")
            fence = (marker[0], len(marker))
            sanitized.append("")
        else:
            sanitized.append(INLINE_CODE.sub("", line))
    return sanitized


def _target_path(source: Path, target: str) -> Path | None:
    target = target.strip()
    if target.startswith("<") and ">" in target:
        target = target[1 : target.index(">")]
    elif " " in target:
        target = target.split(maxsplit=1)[0]

    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or target.startswith("#"):
        return None

    path = unquote(parsed.path)
    if not path:
        return None
    return (source.parent / path).resolve()


def broken_links(root: Path = ROOT) -> list[str]:
    root = root.resolve()
    violations = []
    for source in markdown_files(root):
        lines = _without_code(source.read_text().splitlines())
        for line_number, line in enumerate(lines, 1):
            targets = [*MARKDOWN_LINK.findall(line)]
            definition = REFERENCE_DEFINITION.match(line)
            if definition is not None:
                targets.append(definition.group(1))
            for raw_target in targets:
                target = _target_path(source, raw_target)
                if target is None:
                    continue
                inside_root = target == root or target.is_relative_to(root)
                if inside_root and target.exists():
                    continue
                relative = source.relative_to(root)
                violations.append(f"{relative}:{line_number}: {raw_target}")
    return violations


def main() -> int:
    violations = broken_links()
    if violations:
        print("Broken local Markdown links found:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1
    print(f"Markdown local links are valid ({len(markdown_files())} files checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
