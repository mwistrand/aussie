import tempfile
import unittest
from pathlib import Path

from docs_contract import (
    broken_links,
    configuration_violations,
    prometheus_metric_violations,
    toolchain_violations,
)


class DocumentationContractTest(unittest.TestCase):

    def write_metric_fixture(self, root, source, rules):
        java_source = root / "api/src/main/java/aussie/Metrics.java"
        java_source.parent.mkdir(parents=True, exist_ok=True)
        java_source.write_text(source)
        prometheus_rules = root / "monitoring/prometheus/alerts/aussie.yaml"
        prometheus_rules.parent.mkdir(parents=True, exist_ok=True)
        prometheus_rules.write_text(rules)

    def test_existing_local_link_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs").mkdir()
            (root / "docs/guide.md").write_text("[reference](reference.md)\n")
            (root / "docs/reference.md").write_text("# Reference\n")

            self.assertEqual(broken_links(root), [])

    def test_missing_local_link_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text("[missing](missing.md)\n")

            self.assertEqual(broken_links(root), ["guide.md:1: missing.md"])

    def test_external_and_fragment_links_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text(
                "[site](https://example.com) [section](#section) [mail](mailto:a@example.com)\n"
            )

            self.assertEqual(broken_links(root), [])

    def test_link_outside_repository_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text("[outside](../outside.md)\n")

            self.assertEqual(broken_links(root), ["guide.md:1: ../outside.md"])

    def test_code_examples_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text(
                "`[inline](missing-inline.md)`\n\n"
                "```markdown\n"
                "[fenced](missing-fenced.md)\n"
                "```\n"
            )

            self.assertEqual(broken_links(root), [])

    def test_reference_definition_destination_is_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guide.md").write_text("[guide][ref]\n\n[ref]: missing.md\n")

            self.assertEqual(broken_links(root), ["guide.md:3: missing.md"])

    def test_ignored_directory_names_on_checkout_ancestors_do_not_disable_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "build" / "aussie"
            root.mkdir(parents=True)
            (root / "guide.md").write_text("[missing](missing.md)\n")

            self.assertEqual(broken_links(root), ["guide.md:1: missing.md"])

    def test_declared_prometheus_metric_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_metric_fixture(
                root,
                'Counter.builder("aussie.requests.total").register(registry);\n',
                "expr: sum(rate(aussie_requests_total[5m]))\n",
            )

            self.assertEqual(prometheus_metric_violations(root), [])

    def test_counter_base_name_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_metric_fixture(
                root,
                'Counter.builder("aussie.gateway.results").register(registry);\n',
                "expr: aussie_gateway_results > 0\n",
            )

            self.assertEqual(
                prometheus_metric_violations(root),
                ["monitoring/prometheus/alerts/aussie.yaml:1: aussie_gateway_results"],
            )

    def test_timer_max_is_accepted_but_non_histogram_bucket_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_metric_fixture(
                root,
                'Timer.builder("aussie.dns.lookup.latency").register(registry);\n',
                "expr: aussie_dns_lookup_latency_seconds_max > 0\n"
                "other: aussie_dns_lookup_latency_seconds_bucket > 0\n",
            )

            self.assertEqual(
                prometheus_metric_violations(root),
                ["monitoring/prometheus/alerts/aussie.yaml:2: aussie_dns_lookup_latency_seconds_bucket"],
            )

    def test_timer_quantile_series_is_conditional(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_metric_fixture(
                root,
                'Timer.builder("aussie.request.latency").register(registry);\n',
                "expr: aussie_request_latency_seconds > 0\n",
            )

            self.assertEqual(
                prometheus_metric_violations(root),
                ["monitoring/prometheus/alerts/aussie.yaml:1: aussie_request_latency_seconds"],
            )

            self.write_metric_fixture(
                root,
                'Timer.builder("aussie.request.latency")'
                '.publishPercentiles(0.95)'
                '.register(registry);\n',
                "expr: aussie_request_latency_seconds > 0\n",
            )

            self.assertEqual(prometheus_metric_violations(root), [])

    def test_histogram_bucket_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_metric_fixture(
                root,
                'Timer.builder("aussie.proxy.latency")'
                '.publishPercentileHistogram()'
                '.register(registry);\n',
                "expr: histogram_quantile(0.99, aussie_proxy_latency_seconds_bucket)\n",
            )

            self.assertEqual(prometheus_metric_violations(root), [])

    def test_toolchain_versions_and_public_prerequisites_are_aligned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cli").mkdir()
            (root / ".github/workflows").mkdir(parents=True)
            (root / "docs/platform").mkdir(parents=True)
            (root / "cli/go.mod").write_text("module example\n\ngo 1.23\n")
            (root / "cli/.tool-versions").write_text("java openjdk-25\n")
            (root / ".github/workflows/ci.yml").write_text(
                "java-version: '25'\nnode-version: '24'\n"
            )
            (root / "README.md").write_text("Java 25 Go 1.23+ Node.js 24")
            (root / "docs/platform/README.md").write_text("Java 25 Go 1.23+ Node.js 24")
            (root / "docs/platform/e2e-tests.md").write_text("Java 25 Node.js 24")

            self.assertEqual(toolchain_violations(root), [])

    def test_toolchain_drift_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cli").mkdir()
            (root / ".github/workflows").mkdir(parents=True)
            (root / "docs/platform").mkdir(parents=True)
            (root / "cli/go.mod").write_text("module example\n\ngo 1.23\n")
            (root / "cli/.tool-versions").write_text("java openjdk-25\n")
            (root / ".github/workflows/ci.yml").write_text(
                "java-version: '21'\nnode-version: '22'\n"
            )
            for path in (
                root / "README.md",
                root / "docs/platform/README.md",
                root / "docs/platform/e2e-tests.md",
            ):
                path.write_text("")

            violations = toolchain_violations(root)

            self.assertTrue(any("Java versions" in violation for violation in violations))
            self.assertTrue(any("Node.js versions" in violation for violation in violations))
            self.assertTrue(any("README.md: missing Java 25" in violation for violation in violations))

    def test_configuration_names_are_checked_against_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "guidebook").mkdir()
            (root / "docs/platform").mkdir(parents=True)
            (root / "api/src/main/resources").mkdir(parents=True)
            (root / "guidebook/appendix-b-configuration-inventory.md").write_text(
                "| `aussie.gateway.limits.max-body-size` | `int` | `1` | `AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE` | no | source |\n"
                "| `aussie.auth.route-auth.providers.*.issuer` | `String` | _(not set)_ | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_ISSUER` | no | source |\n"
            )
            (root / "guidebook/appendix-b-configuration-reference.md").write_text(
                "| `aussie.gateway.limits.max-body-size` | `int` | `1` | `AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE` |\n"
                "| `aussie.auth.route-auth.providers.demo.issuer` | `String` | - | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_DEMO_ISSUER` |\n"
                "| `aussie.gateway.limits.removed` | `int` | `1` | `AUSSIE_REMOVED` |\n"
                "| `aussie.gateway.limits.max-body-size.bytes` | `int` | `1` | `AUSSIE_REMOVED_BYTES` |\n"
                "| `aussie.auth.route-auth.providers.demo.issuer` | `String` | - | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_DEMO_ISSUER_TYPO` |\n"
            )

            violations = configuration_violations(root)

            self.assertEqual(
                violations,
                [
                    "guidebook/appendix-b-configuration-reference.md:3: aussie.gateway.limits.removed",
                    "guidebook/appendix-b-configuration-reference.md:4: aussie.gateway.limits.max-body-size.bytes",
                    "guidebook/appendix-b-configuration-reference.md:3: AUSSIE_REMOVED",
                    "guidebook/appendix-b-configuration-reference.md:4: AUSSIE_REMOVED_BYTES",
                    "guidebook/appendix-b-configuration-reference.md:5: AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_DEMO_ISSUER_TYPO",
                ],
            )


if __name__ == "__main__":
    unittest.main()
