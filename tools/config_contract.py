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
    config_type: str | None = None
    declaring_type: str | None = None
    parent_type: str | None = None
    parent_method: str | None = None
    accessor_chain: tuple[str, ...] = ()


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

            def visit(
                item: Interface,
                prefix: str,
                accessor_chain: tuple[str, ...],
                parent_type: str | None = None,
                parent_method: str | None = None,
            ) -> None:
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
                        nested_chain = (*accessor_chain, method)
                        if map_value is not None and resolve_interface(map_value.group(1), item, by_file, by_name) is nested:
                            nested_chain += ("*",)
                        visit(nested, property_name, nested_chain, item.name, method)
                        continue
                    default_match = re.search(r'@WithDefault\s*\(\s*"([^"]*)"', annotations)
                    properties.append(
                        Property(
                            property_name,
                            " ".join(value_type.split()),
                            default_match.group(1) if default_match else None,
                            str(item.path.relative_to(ROOT)),
                            3,
                            config_type=root_interface.name,
                            declaring_type=item.name,
                            parent_type=parent_type,
                            parent_method=parent_method,
                            accessor_chain=(*accessor_chain, method),
                        )
                    )

            visit(root_interface, root.group(1), ())
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


def scheduled_properties() -> list[Property]:
    """Find application settings consumed by Quarkus scheduler placeholders."""
    properties: list[Property] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        source = strip_comments(path.read_text())
        for annotation in re.finditer(r"@Scheduled\s*\((.*?)\)", source, re.DOTALL):
            body = annotation.group(1)
            for match in re.finditer(
                r"\$\{(aussie\.[A-Za-z0-9_.-]+):([^}]*)\}", body
            ):
                value_type = "cron" if re.search(r"\bcron\s*=", body[: match.start()]) else "Duration"
                properties.append(
                    Property(
                        match.group(1),
                        value_type,
                        match.group(2),
                        str(path.relative_to(ROOT)),
                        4,
                    )
                )
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


def active_property_references(lines: list[str]) -> set[str]:
    references: set[str] = set()
    for line in lines:
        line = line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        value = line.split("=", 1)[1]
        for match in re.finditer(r"\$\{(aussie\.[A-Za-z0-9_.-]+)(?::[^}]*)?\}", value):
            references.add(match.group(1))
    return references


def application_property_references() -> set[str]:
    references: set[str] = set()
    for path in sorted(APPLICATION_RESOURCES.glob("application*.properties")):
        references.update(active_property_references(path.read_text().splitlines()))
    return references


def documented_properties() -> set[str]:
    return set(re.findall(r"`((?:aussie|quarkus)\.[A-Za-z0-9_.-]+)", REFERENCE.read_text()))


def collect() -> list[Property]:
    by_name: dict[str, Property] = {}
    for item in mapping_properties() + explicit_properties() + scheduled_properties():
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


# shortcut: static source evidence cannot prove behavior; add a focused test for conditional effects.
def has_runtime_consumer(
    item: Property,
    java_sources: dict[Path, str],
    property_references: set[str],
    type_parents: dict[str, set[str]],
) -> bool:
    source_path = ROOT / item.source
    if any(
        item.name in source or item.name.endswith(".*") and item.name[:-2] in source
        for source in java_sources.values()
    ):
        return True

    if any(matches(item.name, reference) for reference in property_references):
        return True

    leaf = item.name.rsplit(".", 1)[-1].removesuffix("*")
    if item.name in DYNAMIC_PROPERTIES and any(
        re.search(r'"' + re.escape(leaf) + r'"', source) for source in java_sources.values()
    ):
        return True
    if item.config_type is None or item.declaring_type is None or not item.accessor_chain:
        return False
    method_chain = [
        re.sub(r"-([a-z])", lambda match: match.group(1).upper(), method)
        for method in item.accessor_chain
        if method != "*"
    ]

    def type_names(type_name: str) -> str:
        return r"(?:" + re.escape(type_name) + r"|\b" + re.escape(type_name.rsplit(".", 1)[-1]) + r")"

    def receivers(source: str, type_name: str) -> list[str]:
        type_pattern = type_names(type_name)
        names = re.findall(r"\b" + type_pattern + r"\s+(\w+)\b", source)
        names.extend(
            name + r"\s*\.\s*get\s*\(\s*\)"
            for name in re.findall(r"\bInstance\s*<\s*" + type_pattern + r"\s*>\s+(\w+)\b", source)
        )
        names.extend(
            name + r"\s*\(\s*\)"
            for name in re.findall(r"\b" + type_pattern + r"\s+(\w+)\s*\(", source)
        )
        return names

    def compatible_types(type_name: str) -> set[str]:
        compatible = {type_name.rsplit(".", 1)[-1]}
        pending = list(compatible)
        while pending:
            current = pending.pop()
            for parent in type_parents.get(current, set()):
                if parent not in compatible:
                    compatible.add(parent)
                    pending.append(parent)
        return compatible

    def matches_chain(source: str, receiver: str, chain: list[str]) -> bool:
        expression = r"\b" + receiver
        for method in chain:
            expression += r"\s*\.\s*" + re.escape(method) + r"\s*\(\s*\)"
        return re.search(expression, source) is not None

    def aliases_from(source: str, receiver: str, chain: list[str]) -> list[str]:
        expression = r"\b" + receiver
        for method in chain:
            expression += r"\s*\.\s*" + re.escape(method) + r"\s*\(\s*\)"
        assignment = (
            r"\b(?:final\s+)?(?:var|[A-Za-z_][\w.<>,?\[\]]*\s+)"
            r"(\w+)\s*=\s*[^;]*"
            + expression
        )
        return re.findall(assignment, source)

    def has_leaf_access(source: str, receiver: str, prefix: list[str], leaf_method: str) -> bool:
        return any(
            matches_chain(source, alias, [leaf_method])
            for alias in aliases_from(source, receiver, prefix)
        )

    def has_accessor_access(
        source: str, receiver: str, chain: list[str], seen: set[tuple[str, tuple[str, ...]]] | None = None
    ) -> bool:
        seen = set() if seen is None else seen
        key = (receiver, tuple(chain))
        if key in seen:
            return False
        seen.add(key)
        if matches_chain(source, receiver, chain):
            return True
        for alias in aliases_from(source, receiver, []):
            if has_accessor_access(source, alias, chain, seen):
                return True
        for split in range(1, len(chain)):
            for alias in aliases_from(source, receiver, chain[:split]):
                if has_accessor_access(source, alias, chain[split:], seen):
                    return True
        return False

    def has_map_leaf_access(source: str, leaf_method: str) -> bool:
        values = re.findall(r"\b(?:var|[A-Za-z_][\w.<>,?\[\]]*\s+)(\w+)\s*=\s*\w+\.getValue\s*\(\s*\)", source)
        return any(matches_chain(source, value, [leaf_method]) for value in values)

    for path, source in java_sources.items():
        if path == source_path:
            continue
        for receiver in receivers(source, item.config_type):
            if "*" in item.accessor_chain:
                prefix = method_chain[: item.accessor_chain.index("*")]
                if matches_chain(source, receiver, prefix) and re.search(
                    r"\.\s*" + re.escape(method_chain[-1]) + r"\s*\(", source
                ):
                    if has_leaf_access(source, receiver, prefix, method_chain[-1]) or has_map_leaf_access(
                        source, method_chain[-1]
                    ):
                        return True
            elif has_accessor_access(source, receiver, method_chain):
                return True
        if item.parent_type is not None and item.parent_method is not None:
            for receiver in receivers(source, item.parent_type):
                if has_leaf_access(source, receiver, [item.parent_method], method_chain[-1]):
                    return True
        for declaring_type in compatible_types(item.declaring_type):
            for receiver in receivers(source, declaring_type):
                if "*" in item.accessor_chain:
                    if has_leaf_access(source, receiver, [], method_chain[-1]):
                        return True
                elif has_accessor_access(source, receiver, [method_chain[-1]]):
                    return True
    return False


def check(properties: list[Property]) -> list[str]:
    patterns = [item.name for item in properties]
    errors: list[str] = []
    java_sources = {path: strip_comments(path.read_text()) for path in JAVA_ROOT.rglob("*.java")}
    property_references = application_property_references()
    type_parents: dict[str, set[str]] = {}
    for source in java_sources.values():
        for declaration in re.finditer(
            r"\b(?:interface|class)\s+(\w+)\s+(?:extends|implements)\s+([^\{\n]+)\{", source
        ):
            type_parents[declaration.group(1)] = {
                parent.rsplit(".", 1)[-1]
                for parent in re.findall(r"\b([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\b", declaration.group(2))
            }
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
    for item in properties:
        if not has_runtime_consumer(item, java_sources, property_references, type_parents):
            errors.append(f"accepted configuration property has no runtime consumer: {item.name}")
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
