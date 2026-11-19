#!/usr/bin/env python3
"""Check the repository-owned Kubernetes deployment contract."""

from __future__ import annotations

import ast
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
ROLLBACK = ROOT / ".github/workflows/rollback.yml"


def parse_scalar(value: str) -> Any:
    value = value.strip()
    if not value:
        return None
    if value in ("true", "True"):
        return True
    if value in ("false", "False"):
        return False
    if value in ("null", "Null", "NULL", "~"):
        return None
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        return [] if not inner else [parse_scalar(item) for item in inner.split(",")]
    if value[0:1] in ('"', "'") and value[-1:] == value[0]:
        try:
            return ast.literal_eval(value)
        except (SyntaxError, ValueError):
            return value[1:-1]
    try:
        return int(value)
    except ValueError:
        return value


def split_key_value(content: str) -> tuple[str, str]:
    key, separator, value = content.partition(":")
    if not separator or not key.strip():
        raise ValueError(f"invalid YAML mapping entry: {content}")
    return key.strip(), value.strip()


def parse_yaml_document(text: str) -> dict[str, Any]:
    # shortcut: this parser covers kubectl kustomize's plain manifests; use a YAML library if anchors or multiline values are added.
    lines = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        lines.append((indent, raw.strip()))

    def parse_block(index: int, indent: int) -> tuple[Any, int]:
        if index >= len(lines) or lines[index][0] < indent:
            return {}, index
        is_list = lines[index][1] == "-" or lines[index][1].startswith("- ")
        result: Any = [] if is_list else {}
        while index < len(lines) and lines[index][0] == indent:
            content = lines[index][1]
            if is_list:
                if not (content == "-" or content.startswith("- ")):
                    break
                item = content[1:].strip()
                index += 1
                if not item:
                    value, index = parse_block(index, lines[index][0])
                elif ":" in item:
                    key, raw_value = split_key_value(item)
                    value = {key: parse_scalar(raw_value)} if raw_value else {key: None}
                    if index < len(lines) and lines[index][0] > indent:
                        nested, index = parse_block(index, lines[index][0])
                        if not raw_value:
                            value[key] = nested
                            if index < len(lines) and lines[index][0] > indent and not lines[index][1].startswith("-"):
                                continuation, index = parse_block(index, lines[index][0])
                                if isinstance(continuation, dict):
                                    value.update(continuation)
                        elif isinstance(nested, dict):
                            value.update(nested)
                        else:
                            raise ValueError(f"invalid list mapping for {key}: {nested!r}")
                else:
                    value = parse_scalar(item)
                result.append(value)
            else:
                if content.startswith("- ") or content == "-":
                    break
                key, raw_value = split_key_value(content)
                index += 1
                if raw_value:
                    result[key] = parse_scalar(raw_value)
                elif index < len(lines) and (
                    lines[index][0] > indent
                    or (lines[index][0] == indent and lines[index][1].startswith("-"))
                ):
                    result[key], index = parse_block(index, lines[index][0])
                else:
                    result[key] = None
        return result, index

    if not lines:
        return {}
    document, index = parse_block(0, lines[0][0])
    if index != len(lines) or not isinstance(document, dict):
        raise ValueError("unsupported rendered Kubernetes YAML")
    return document


def parse_yaml_documents(text: str) -> list[dict[str, Any]]:
    """Parse the plain YAML emitted by kubectl kustomize without extra deps."""
    return [
        document
        for raw_document in re.split(r"(?m)^---\s*$", text)
        if raw_document.strip() and (document := parse_yaml_document(raw_document))
    ]


def resource(documents: list[dict[str, Any]], kind: str, name: str) -> dict[str, Any]:
    matches = [
        document
        for document in documents
        if document.get("kind") == kind and document.get("metadata", {}).get("name") == name
    ]
    if len(matches) != 1:
        raise AssertionError(f"expected one {kind}/{name}, found {len(matches)}")
    return matches[0]


def require_equal(actual: Any, expected: Any, description: str) -> None:
    if actual != expected:
        raise AssertionError(f"{description}: expected {expected!r}, got {actual!r}")


def require_match(actual: Any, pattern: str, description: str) -> None:
    if not isinstance(actual, str) or re.fullmatch(pattern, actual) is None:
        raise AssertionError(f"{description}: expected {pattern!r}, got {actual!r}")


def validate_deployment(deployment: dict[str, Any]) -> None:
    spec = deployment["spec"]
    require_equal(spec["replicas"], 3, "gateway replica count")
    strategy = spec["strategy"]
    require_equal(strategy["type"], "RollingUpdate", "deployment strategy")
    require_equal(strategy["rollingUpdate"]["maxUnavailable"], 0, "maximum unavailable replicas")
    require_equal(strategy["rollingUpdate"]["maxSurge"], 1, "maximum surge replicas")

    template_spec = spec["template"]["spec"]
    require_equal(template_spec["serviceAccountName"], "aussie", "service account")
    require_equal(template_spec["terminationGracePeriodSeconds"], 45, "termination grace period")
    require_equal(template_spec["securityContext"]["runAsNonRoot"], True, "pod non-root setting")
    require_equal(template_spec["securityContext"]["seccompProfile"]["type"], "RuntimeDefault", "pod seccomp")
    spread = template_spec["topologySpreadConstraints"]
    require_equal(len(spread), 1, "topology spread constraint count")
    require_equal(spread[0]["maxSkew"], 1, "topology spread skew")
    require_equal(spread[0]["topologyKey"], "topology.kubernetes.io/zone", "topology spread key")
    require_equal(spread[0]["whenUnsatisfiable"], "DoNotSchedule", "topology spread enforcement")

    container = next(
        (item for item in template_spec["containers"] if item.get("name") == "aussie"),
        None,
    )
    if container is None:
        raise AssertionError("gateway container is missing")
    require_match(container["image"], r".+@sha256:.+", "digest-pinned gateway image")
    require_equal(container["securityContext"]["allowPrivilegeEscalation"], False, "privilege escalation")
    require_equal(container["securityContext"]["readOnlyRootFilesystem"], True, "read-only root filesystem")
    require_equal(container["securityContext"]["capabilities"]["drop"], ["ALL"], "dropped capabilities")
    require_equal(container["resources"]["requests"], {"cpu": "500m", "memory": "1Gi"}, "resource requests")
    require_equal(container["resources"]["limits"], {"cpu": 2, "memory": "2Gi"}, "resource limits")
    require_equal(container["envFrom"], [{"configMapRef": {"name": "aussie"}}], "config map reference")

    expected_secret_keys = {
        "CASSANDRA_USERNAME",
        "CASSANDRA_PASSWORD",
        "REDIS_PASSWORD",
        "AUTH_ENCRYPTION_KEY",
    }
    actual_secret_keys = {
        item["name"]
        for item in container["env"]
        if item.get("valueFrom", {}).get("secretKeyRef", {}).get("name") == "aussie-production-secrets"
    }
    require_equal(actual_secret_keys, expected_secret_keys, "production secret references")

    expected_probes = {
        "startupProbe": "/q/health/started",
        "readinessProbe": "/q/health/ready",
        "livenessProbe": "/q/health/live",
    }
    for probe_name, path in expected_probes.items():
        probe = container[probe_name]
        require_equal(probe["httpGet"], {"path": path, "port": "http"}, f"{probe_name} HTTP probe")


def validate_network_policy(policy: dict[str, Any]) -> None:
    spec = policy["spec"]
    require_equal(spec["policyTypes"], ["Ingress", "Egress"], "network policy types")
    actual_ports = {
        (entry["port"], entry["protocol"])
        for rule in spec["egress"]
        for entry in rule.get("ports", [])
    }
    require_equal(
        actual_ports,
        {(53, "UDP"), (53, "TCP"), (9042, "TCP"), (6379, "TCP"), (4317, "TCP")},
        "in-cluster dependency and telemetry ports",
    )


def validate_config_map(config_map: dict[str, Any]) -> None:
    data = config_map["data"]
    require_equal(data["AUSSIE_TELEMETRY_ENABLED"], "true", "production telemetry master switch")
    require_equal(data["AUSSIE_TELEMETRY_TRACING_ENABLED"], "true", "production tracing switch")
    require_match(data["OTEL_EXPORTER_OTLP_ENDPOINT"], r"https?://.+:4317", "OTLP collector endpoint")


def validate_rendered_contract(text: str, external: bool = False) -> None:
    documents = parse_yaml_documents(text)
    required = {
        "ServiceAccount": "aussie",
        "ConfigMap": "aussie",
        "Deployment": "aussie",
        "Service": "aussie",
        "PodDisruptionBudget": "aussie",
        "HorizontalPodAutoscaler": "aussie",
    }
    for kind, name in required.items():
        resource(documents, kind, name)
    validate_config_map(resource(documents, "ConfigMap", "aussie"))
    validate_deployment(resource(documents, "Deployment", "aussie"))
    validate_network_policy(resource(documents, "NetworkPolicy", "aussie"))
    require_equal(
        resource(documents, "ServiceAccount", "aussie")["automountServiceAccountToken"],
        False,
        "service account token mount",
    )
    require_equal(
        resource(documents, "PodDisruptionBudget", "aussie")["spec"]["minAvailable"],
        2,
        "pod disruption budget",
    )
    if external:
        policy = resource(documents, "NetworkPolicy", "aussie-external-dependencies")
        blocks = {
            rule["to"][0]["ipBlock"]["cidr"]: rule["ports"]
            for rule in policy["spec"]["egress"]
        }
        require_equal(
            blocks["192.0.2.10/32"],
            [{"protocol": "TCP", "port": 9042}],
            "external Cassandra egress",
        )
        require_equal(
            blocks["192.0.2.11/32"],
            [{"protocol": "TCP", "port": 6379}],
            "external Redis egress",
        )
        require_equal(
            blocks["192.0.2.12/32"],
            [{"protocol": "TCP", "port": 4317}],
            "external telemetry egress",
        )


def rollback_container_default(workflow: str) -> str:
    match = re.search(
        r"(?ms)^      container:\n(?P<body>(?:        .*\n)+?)(?=^      [A-Za-z_]+:|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError("rollback container input is missing")
    default = re.search(r"^        default:\s*(\S+)\s*$", match.group("body"), re.MULTILINE)
    if default is None:
        raise AssertionError("rollback container default is missing")
    return default.group(1).strip("'\"")


def render(path: str) -> str:
    try:
        result = subprocess.run(
            ["kubectl", "kustomize", path],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as error:
        raise RuntimeError("kubectl is required for the Kubernetes contract check") from error
    return result.stdout


def check() -> None:
    validate_rendered_contract(render("deploy/kubernetes"))
    validate_rendered_contract(render("deploy/kubernetes/overlays/external-dependencies"), external=True)
    require_equal(rollback_container_default(ROLLBACK.read_text()), "aussie", "rollback container default")


if __name__ == "__main__":
    try:
        check()
    except (AssertionError, RuntimeError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Kubernetes contract failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    print("Kubernetes deployment contract passed")
