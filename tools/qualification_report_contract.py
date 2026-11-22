#!/usr/bin/env python3
"""Validate the release qualification report's evidence contract."""

from __future__ import annotations

import argparse
import math
import re
import sys
from pathlib import Path


IMAGE = re.compile(r"^[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
SECONDS = re.compile(r"^(?:\d+(?:\.\d*)?|\.\d+)s$")
ENDPOINT_RESULT = re.compile(
    r"^Endpoint (?P<endpoint>\S+): total=(?P<total>\d+) "
    r"success=(?P<success>\d+) failed=(?P<failed>\d+) "
    r"p50=(?P<p50>(?:\d+(?:\.\d*)?|\.\d+))s "
    r"p95=(?P<p95>(?:\d+(?:\.\d*)?|\.\d+))s "
    r"p99=(?P<p99>(?:\d+(?:\.\d*)?|\.\d+))s$"
)
SUSTAINED_LOAD_SECTION = re.compile(
    r"^## Sustained operational and routed HTTP load\s*\n```text\s*\n(?P<body>.*?)\n```",
    re.MULTILINE | re.DOTALL,
)

REQUIRED_FIELDS = {
    "Result",
    "Git commit",
    "Artifact",
    "Qualification image",
    "Production profile",
    "Cassandra image",
    "Redis image",
    "Load duration",
    "Load interval",
    "Load p95 limit",
    "Recovery time limit",
    "Load endpoints",
}

REQUIRED_PASSES = {
    "Redis recovery",
    "Cassandra recovery",
    "DNS resolver stall",
    "DNS NetworkPolicy enforcement",
    "Gateway pod-loss recovery",
    "Node drain",
    "Gateway rolling deployment",
    "Gateway rollback",
    "Routed HTTP convergence",
    "WebSocket handshake",
    "WebSocket capacity",
    "WebSocket drain",
    "Telemetry exporter stall",
}


def _fields(report: str) -> dict[str, str]:
    fields = {}
    for line in report.splitlines():
        match = re.fullmatch(r"- ([^:]+): (.*)", line)
        if match:
            fields[match.group(1)] = match.group(2)
    return fields


def _positive_seconds(fields: dict[str, str], name: str, errors: list[str]) -> float | None:
    value = fields.get(name, "").strip()
    if not value:
        errors.append(f"{name} must be a non-empty positive duration in seconds")
        return None
    if SECONDS.fullmatch(value) is None:
        errors.append(f"{name} must be a positive duration ending in 's'")
        return None
    seconds = float(value[:-1])
    if not math.isfinite(seconds) or seconds <= 0:
        errors.append(f"{name} must be greater than zero")
        return None
    return seconds


def _validate_sustained_load(
    report: str, fields: dict[str, str], p95_limit: float | None, errors: list[str]
) -> None:
    match = SUSTAINED_LOAD_SECTION.search(report)
    if match is None or match.group("body").strip() in {"", "not run"}:
        errors.append("sustained-load results are missing or marked not run")
        return

    endpoints = [endpoint.strip() for endpoint in fields.get("Load endpoints", "").split(",")]
    endpoints = [endpoint for endpoint in endpoints if endpoint]
    results = {}
    for line in match.group("body").splitlines():
        result = ENDPOINT_RESULT.fullmatch(line.strip())
        if result is not None:
            endpoint = result.group("endpoint")
            if endpoint in results:
                errors.append(f"sustained-load results duplicate endpoint {endpoint}")
            results[endpoint] = result

    for endpoint in endpoints:
        result = results.get(endpoint)
        if result is None:
            errors.append(f"sustained-load results missing endpoint {endpoint}")
            continue

        total = int(result.group("total"))
        success = int(result.group("success"))
        failed = int(result.group("failed"))
        p95 = float(result.group("p95"))
        if total <= 0:
            errors.append(f"sustained-load endpoint {endpoint} has no requests")
        if success != total or failed != 0:
            errors.append(f"sustained-load endpoint {endpoint} contains failed requests")
        if p95_limit is not None and p95 > p95_limit:
            errors.append(f"sustained-load endpoint {endpoint} exceeds the p95 limit")


def validate_report(
    report_path: Path, expected_image: str, *, allow_experimental: bool = False
) -> list[str]:
    try:
        report = report_path.read_text()
    except OSError as error:
        return [f"unable to read qualification report: {error}"]

    fields = _fields(report)
    errors = []
    missing = sorted(REQUIRED_FIELDS - fields.keys())
    if missing:
        errors.append(f"missing qualification fields: {', '.join(missing)}")

    result = fields.get("Result")
    accepted_results = {"passed"}
    if allow_experimental:
        accepted_results.add("passed (experimental unreleased image)")
    if result not in accepted_results:
        errors.append(f"qualification result must be one of {sorted(accepted_results)}")

    commit = fields.get("Git commit", "")
    if COMMIT.fullmatch(commit) is None:
        errors.append("Git commit must be a 40-character hexadecimal SHA")

    image = fields.get("Qualification image", "")
    if IMAGE.fullmatch(image) is None and not (allow_experimental and image):
        errors.append("Qualification image must be a full image@sha256:<64 hex> reference")
    if expected_image and image != expected_image:
        errors.append("Qualification image does not match the expected image digest")

    if fields.get("Artifact") != "signed release image" and not allow_experimental:
        errors.append("release qualification must identify a signed release image")
    if fields.get("Production profile") != "prod":
        errors.append("qualification must run with the prod profile")

    for field in ("Cassandra image", "Redis image"):
        if not fields.get(field, "").strip():
            errors.append(f"{field} must be non-empty")

    _positive_seconds(fields, "Load duration", errors)
    _positive_seconds(fields, "Load interval", errors)
    p95_limit = _positive_seconds(fields, "Load p95 limit", errors)
    _positive_seconds(fields, "Recovery time limit", errors)
    if not fields.get("Load endpoints", "").strip():
        errors.append("Load endpoints must be non-empty")
    _validate_sustained_load(report, fields, p95_limit, errors)

    for field in REQUIRED_PASSES:
        if fields.get(field) != "passed":
            errors.append(f"{field} must be passed")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--allow-experimental", action="store_true")
    args = parser.parse_args()

    errors = validate_report(args.report, args.image, allow_experimental=args.allow_experimental)
    if errors:
        print("Qualification report contract violations found:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Qualification report is complete and tied to the expected image")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
