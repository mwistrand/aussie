#!/usr/bin/env python3
"""Check and render the Aussie configuration contract."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "api/src/main/java"
APPLICATION_RESOURCES = ROOT / "api/src/main/resources"
INVENTORY = ROOT / "guidebook/appendix-b-configuration-inventory.md"
REFERENCE = ROOT / "guidebook/appendix-b-configuration-reference.md"
DOCUMENTED_REMOVED = {"aussie.auth.jwt."}
# shortcut: this static parser covers the repository's no-arg mappings; use annotation processing if that syntax changes.
DYNAMIC_PROPERTIES = {
    "aussie.auth.storage.cassandra.contact-points": ("String", "localhost:9042"),
    "aussie.auth.storage.cassandra.datacenter": ("String", "datacenter1"),
    "aussie.auth.storage.cassandra.keyspace": ("String", "aussie"),
    "aussie.auth.storage.cassandra.username": ("String", None),
    "aussie.auth.storage.cassandra.password": ("String", None),
}


@dataclass(frozen=True)
class Property:
    name: str
    value_type: str
    default: str | None
    source: str
    quality: int = 0


@dataclass(frozen=True)
class Interface:
    name: str
    body: str
    path: Path


def camel_to_kebab(value: str) -> str:
    return re.sub(r"(?<!^)([A-Z])", r"-\1", value).lower()


def matching_brace(source: str, opening: int) -> int:
    depth = 0
    in_string = False
    escaped = False
    for index in range(opening, len(source)):
        char = source[index]
        if char == '"' and not escaped:
            in_string = not in_string
        escaped = char == "\\" and not escaped
        if in_string:
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
    raise ValueError("unbalanced Java braces")


def strip_comments(source: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\n]*", " ", source, flags=re.DOTALL)


def interfaces() -> tuple[dict[tuple[Path, str], Interface], dict[str, list[Interface]]]:
    by_file: dict[tuple[Path, str], Interface] = {}
    by_name: dict[str, list[Interface]] = {}
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source = path.read_text()
        for match in re.finditer(r"\binterface\s+(\w+)[^{]*\{", source):
            end = matching_brace(source, match.end() - 1)
            item = Interface(match.group(1), source[match.end() : end], path)
            by_file[(path, item.name)] = item
            by_name.setdefault(item.name, []).append(item)
    return by_file, by_name


def interface_methods(body: str) -> list[tuple[str, str, str]]:
    cleaned = strip_comments(body)
    methods: list[tuple[str, str, str]] = []
    start = 0
    depth = 0
    for index, char in enumerate(cleaned):
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        elif char == ";" and depth == 0:
            statement = cleaned[start : index + 1].strip()
            start = index + 1
            match = re.search(
                r"(?s)([\w<>,.? ]+?)\s+(\w+)\s*\(\s*\)\s*;$", statement
            )
            if match:
                methods.append(
                    (match.group(1).strip(), match.group(2), statement[: match.start(1)])
                )
    return methods


def resolve_interface(
    value_type: str,
    current: Interface,
    by_file: dict[tuple[Path, str], Interface],
    by_name: dict[str, list[Interface]],
) -> Interface | None:
    simple = value_type.strip()
    if (current.path, simple) in by_file:
        return by_file[(current.path, simple)]
    imports = {
        match.group(2): match.group(1)
        for match in re.finditer(
            r"import\s+(?:static\s+)?([\w.]+)\.(\w+)\s*;", current.path.read_text()
        )
    }
    if simple in imports:
        expected = ROOT / "api/src/main/java" / Path(imports[simple].replace(".", "/")) / f"{simple}.java"
        for item in by_name.get(simple, []):
            if item.path == expected:
                return item
    package = re.search(r"package\s+([\w.]+)\s*;", current.path.read_text())
    if package:
        expected = ROOT / "api/src/main/java" / Path(package.group(1).replace(".", "/")) / f"{simple}.java"
        for item in by_name.get(simple, []):
            if item.path == expected:
                return item
    candidates = by_name.get(simple, [])
    return candidates[0] if len(candidates) == 1 else None


def mapping_properties() -> list[Property]:
    by_file, by_name = interfaces()
    properties: list[Property] = []
    visited: set[tuple[Path, str, str]] = set()

    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source = path.read_text()
        for root in re.finditer(
            r'@ConfigMapping\s*\(\s*prefix\s*=\s*"([^"]+)"\s*\)\s*(?:public\s+)?interface\s+(\w+)',
            source,
        ):
            root_interface = by_file.get((path, root.group(2)))
            if root_interface is None:
                continue

            def visit(item: Interface, prefix: str) -> None:
                visit_key = (item.path, item.name, prefix)
                if visit_key in visited:
                    return
                visited.add(visit_key)
                for value_type, method, annotations in interface_methods(item.body):
                    name_match = re.search(r'@WithName\s*\(\s*"([^"]+)"', annotations)
                    segment = name_match.group(1) if name_match else camel_to_kebab(method)
                    property_name = f"{prefix}.{segment}"
                    compact_type = value_type.replace(" ", "")
                    nested = resolve_interface(compact_type, item, by_file, by_name)
                    map_value = re.search(r"<[^,]+,\s*([A-Za-z_]\w*)>", value_type)
                    if nested is None and map_value:
                        nested = resolve_interface(map_value.group(1), item, by_file, by_name)
                        if nested is not None:
                            property_name += ".*"
                    if nested is not None:
                        visit(nested, property_name)
                        continue
                    default_match = re.search(r'@WithDefault\s*\(\s*"([^"]*)"', annotations)
                    properties.append(
                        Property(
                            property_name,
                            " ".join(value_type.split()),
                            default_match.group(1) if default_match else None,
                            str(item.path.relative_to(ROOT)),
                            3,
                        )
                    )

            visit(root_interface, root.group(1))
    return properties


def explicit_properties() -> list[Property]:
    properties: list[Property] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source = strip_comments(path.read_text())
        for match in re.finditer(
            r'@ConfigProperty\s*\(\s*name\s*=\s*"([^"]+)"([^)]*)\)', source
        ):
            if not match.group(1).startswith("aussie."):
                continue
            default = re.search(r'defaultValue\s*=\s*"([^"]*)"', match.group(2))
            tail = source[match.end() :]
            type_match = re.match(
                r"\s*([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)?(?:\s*<[^>]+>)?(?:\[\])?)\s+\w+\s*(?=[,)])",
                tail,
                re.DOTALL,
            )
            properties.append(
                Property(
                    match.group(1),
                    type_match.group(1) if type_match else "runtime",
                    default.group(1) if default else None,
                    str(path.relative_to(ROOT)),
                    3,
                )
            )
        for match in re.finditer(
            r'getOrDefault\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*,\s*"([^"]*)"\s*\)',
            source,
        ):
            properties.append(Property(match.group(1), "String", match.group(2), str(path.relative_to(ROOT)), 2))
        for match in re.finditer(
            r'Boolean\.parseBoolean\s*\(\s*config\.getOrDefault\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*,\s*"([^"]*)"\s*\)\s*\)',
            source,
            re.DOTALL,
        ):
            properties.append(Property(match.group(1), "boolean", match.group(2), str(path.relative_to(ROOT)), 3))
        for match in re.finditer(
            r'Duration\.parse\s*\(\s*config\.getOrDefault\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*,\s*"([^"]*)"\s*\)\s*\)',
            source,
            re.DOTALL,
        ):
            properties.append(Property(match.group(1), "Duration", match.group(2), str(path.relative_to(ROOT)), 3))
        for match in re.finditer(
            r'get\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*\)\s*\.orElse\s*\(\s*"([^"]*)"\s*\)',
            source,
        ):
            properties.append(Property(match.group(1), "String", match.group(2), str(path.relative_to(ROOT)), 2))
        for match in re.finditer(
            r'getDuration\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*\)',
            source,
        ):
            properties.append(Property(match.group(1), "Duration", None, str(path.relative_to(ROOT)), 1))
        for match in re.finditer(
            r'get\s*\(\s*"(aussie\.[A-Za-z0-9_.-]+)"\s*\)',
            source,
        ):
            properties.append(Property(match.group(1), "String", None, str(path.relative_to(ROOT)), 1))
        for match in re.finditer(
            r'(?:enabled|value|duration|integer|csv|requireProvider|requireNonBlank|@IfBuildProperty\s*\(\s*name\s*=)\s*\(?'
            r'\s*"(aussie\.[A-Za-z0-9_.-]+)"',
            source,
        ):
            helper = match.group(0).split("(", 1)[0].strip()
            value_type = {
                "enabled": "boolean",
                "value": "String",
                "duration": "Duration",
                "integer": "int",
                "csv": "List<String>",
                "requireProvider": "String",
                "requireNonBlank": "String",
                "@IfBuildProperty": "boolean",
            }.get(helper, "runtime")
            properties.append(Property(match.group(1), value_type, None, str(path.relative_to(ROOT)), 1))
    return properties


def application_properties() -> set[str]:
    names: set[str] = set()
    for path in sorted(APPLICATION_RESOURCES.glob("application*.properties")):
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name = re.sub(r"^%[A-Za-z0-9_-]+\.", "", line.split("=", 1)[0].strip())
            if name.startswith("aussie."):
                names.add(name)
    return names


def documented_properties() -> set[str]:
    return set(re.findall(r"`((?:aussie|quarkus)\.[A-Za-z0-9_.-]+)", REFERENCE.read_text()))


def collect() -> list[Property]:
    by_name: dict[str, Property] = {}
    for item in mapping_properties() + explicit_properties():
        existing = by_name.get(item.name)
        if existing is None or item.quality > existing.quality or (
            item.quality == existing.quality
            and existing.default is None
            and item.default is not None
        ):
            by_name[item.name] = item
    dynamic_source = "api/src/main/java/aussie/adapter/out/storage/cassandra/CassandraSessionRegistry.java"
    for name, (value_type, default) in DYNAMIC_PROPERTIES.items():
        by_name.setdefault(name, Property(name, value_type, default, dynamic_source, 2))
    return sorted(by_name.values(), key=lambda item: item.name)


def matches(pattern: str, name: str) -> bool:
    return re.fullmatch(re.escape(pattern).replace(r"\.\*", r"\.[^.]+"), name) is not None


def env_name(name: str) -> str:
    return re.sub(r"[^A-Z0-9<>]", "_", name.upper().replace(".*", ".<NAME>"))


def sensitive(name: str) -> str:
    sensitive_segments = {"key", "secret", "password", "credential", "client-key", "client-secret", "signing-key"}
    return "yes" if any(segment in sensitive_segments for segment in name.split(".")) else "no"


def render_default(default: str | None) -> str:
    if default is None:
        return "_(not set)_"
    return default if default else "_(empty)_"


def render(properties: list[Property]) -> str:
    lines = [
        "# Generated Aussie configuration inventory",
        "",
        "Do not edit this file directly. Regenerate it with `python3 tools/config_contract.py --write`.",
        "The inventory is derived from `@ConfigMapping`, `@ConfigProperty`, and explicit config lookups.",
        "",
        "| Property | Type | Default | Environment variable | Sensitive | Source |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for item in properties:
        lines.append(
            f"| `{item.name}` | `{item.value_type}` | "
            f"{render_default(item.default)} | "
            f"`{env_name(item.name)}` | {sensitive(item.name)} | `{item.source}` |"
        )
    lines.append("")
    return "\n".join(lines)


def check(properties: list[Property]) -> list[str]:
    patterns = [item.name for item in properties]
    errors: list[str] = []
    for name in sorted(application_properties()):
        if not any(matches(pattern, name) for pattern in patterns):
            errors.append(f"application.properties contains undeclared property: {name}")
    for name in sorted(documented_properties()):
        if name.startswith("quarkus.") or name.startswith(("aussie.adapter.", "aussie.core.", "aussie.spi.")):
            continue
        if name in DOCUMENTED_REMOVED:
            continue
        if not any(matches(pattern, name) or pattern.startswith(name) for pattern in patterns):
            errors.append(f"configuration reference contains unknown property: {name}")
    expected = render(properties)
    if not INVENTORY.exists():
        errors.append(f"generated inventory is missing: {INVENTORY.relative_to(ROOT)}")
    elif INVENTORY.read_text() != expected:
        errors.append(f"generated inventory is stale: {INVENTORY.relative_to(ROOT)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="write the generated inventory")
    args = parser.parse_args()
    properties = collect()
    if args.write:
        INVENTORY.write_text(render(properties))
        return 0
    errors = check(properties)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"configuration contract is current ({len(properties)} properties)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
