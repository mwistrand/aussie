import tempfile
import unittest
from pathlib import Path

from docs_contract import broken_links, prometheus_metric_violations


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


if __name__ == "__main__":
    unittest.main()
