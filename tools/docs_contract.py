#!/usr/bin/env python3
"""Check that documented repository references and monitoring names stay valid."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
JAVA_SOURCE = ROOT / "api/src/main/java"
PROMETHEUS = ROOT / "monitoring/prometheus"
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


def declared_metrics(root: Path = ROOT) -> set[str]:
    """Return the Prometheus series emitted by the Micrometer builders in Java."""
    java_root = root / JAVA_SOURCE.relative_to(ROOT)
    metrics: set[str] = set()
    builder = re.compile(
        r"\b(Counter|Timer|Gauge)\.builder\(\s*\"([^\"]+)\"(?P<body>.*?)\.register\s*\(",
        re.DOTALL,
    )
    for source in java_root.rglob("*.java"):
        for kind, name, body in builder.findall(source.read_text()):
            base = name.replace(".", "_")
            if kind == "Counter":
                metrics.add(base if base.endswith("_total") else f"{base}_total")
                continue
            if kind == "Gauge":
                metrics.add(base)
                continue

            # Prometheus timers always expose count, sum, and max in seconds.
            seconds = f"{base}_seconds"
            metrics.update(f"{seconds}_{suffix}" for suffix in ("count", "sum", "max"))
            if ".publishPercentiles(" in body:
                # Percentiles are samples of the base timer name with a quantile label.
                metrics.add(seconds)
            if ".publishPercentileHistogram(" in body:
                metrics.add(f"{seconds}_bucket")
    return metrics


def prometheus_metric_violations(root: Path = ROOT) -> list[str]:
    """Reject alert rules that refer to a custom metric absent from Java."""
    prometheus_root = root / PROMETHEUS.relative_to(ROOT)
    available = declared_metrics(root)
    metric_pattern = re.compile(r"\baussie_[a-z][a-z0-9_]*\b")
    violations = []
    for path in sorted(prometheus_root.rglob("*.y*ml")):
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            for metric in sorted(set(metric_pattern.findall(line))):
                if metric in available:
                    continue
                relative = path.relative_to(root)
                violations.append(f"{relative}:{line_number}: {metric}")
    return violations


def toolchain_violations(root: Path = ROOT) -> list[str]:
    """Keep public prerequisites and CI tool versions aligned with source metadata."""
    root = root.resolve()
    go_mod = root / "cli/go.mod"
    tool_versions = root / "cli/.tool-versions"
    workflows = root / ".github/workflows"
    errors = []

    go_match = re.search(r"^go\s+(\d+(?:\.\d+)?)\s*$", go_mod.read_text(), re.MULTILINE)
    java_match = re.search(r"^java\s+openjdk-(\d+)\s*$", tool_versions.read_text(), re.MULTILINE)
    if go_match is None or java_match is None:
        return ["toolchain metadata must declare Go and Java versions"]

    go_version = go_match.group(1)
    java_version = java_match.group(1)
    java_versions = set()
    node_versions = set()
    for workflow in (*workflows.glob("*.yml"), *workflows.glob("*.yaml")):
        source = workflow.read_text()
        java_versions.update(re.findall(r"java-version:\s*['\"]?(\d+)", source))
        node_versions.update(re.findall(r"node-version:\s*['\"]?(\d+)", source))

    if java_versions != {java_version}:
        errors.append(f"CI Java versions {sorted(java_versions)} do not match Java {java_version}")
    if node_versions != {"24"}:
        errors.append(f"CI Node.js versions {sorted(node_versions)} do not match Node.js 24")

    required = {
        root / "README.md": (f"Java {java_version}", f"Go {go_version}+", "Node.js 24"),
        root / "docs/platform/README.md": (f"Java {java_version}", f"Go {go_version}+", "Node.js 24"),
        root / "docs/platform/e2e-tests.md": (f"Java {java_version}", "Node.js 24"),
    }
    for path, snippets in required.items():
        source = path.read_text()
        for snippet in snippets:
            if snippet not in source:
                errors.append(f"{path.relative_to(root)}: missing {snippet}")
    return errors


def main() -> int:
    violations = [*broken_links(), *prometheus_metric_violations(), *toolchain_violations()]
    if violations:
        print("Documentation contract violations found:", file=sys.stderr)
        print("\n".join(violations), file=sys.stderr)
        return 1
    print(
        f"Documentation contract is valid ({len(markdown_files())} Markdown files, "
        f"{len(declared_metrics())} metrics checked)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
