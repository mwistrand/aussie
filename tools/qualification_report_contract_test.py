import tempfile
import unittest
from pathlib import Path

from qualification_report_contract import validate_report


IMAGE = "ghcr.io/mwistrand/aussie@sha256:" + "a" * 64
COMMIT = "b" * 40
ENDPOINTS = [
    "/q/health/ready",
    "/q/health/live",
    "/q/metrics",
    "/qualification-upstream/health",
]


def load_output(**overrides):
    defaults = {
        endpoint: {
            "total": 10,
            "success": 10,
            "failed": 0,
            "p50": "0.001",
            "p95": "0.002",
            "p99": "0.003",
        }
        for endpoint in ENDPOINTS
    }
    defaults.update(overrides)
    return "\n".join(
        "Endpoint {endpoint}: total={total} success={success} failed={failed} "
        "p50={p50}s p95={p95}s p99={p99}s".format(endpoint=endpoint, **values)
        for endpoint, values in defaults.items()
    )


def report(sustained_load=None, **overrides):
    fields = {
        "Result": "passed",
        "Git commit": COMMIT,
        "Artifact": "signed release image",
        "Qualification image": IMAGE,
        "Production profile": "prod",
        "Cassandra image": "cassandra:4.1.4",
        "Redis image": "redis:7.2.5-alpine",
        "Load duration": "30s",
        "Load interval": "0.1s",
        "Load p95 limit": "1s",
        "Recovery time limit": "120s",
        "Load endpoints": ", ".join(ENDPOINTS),
        "Redis recovery": "passed",
        "Cassandra recovery": "passed",
        "DNS resolver stall": "passed",
        "DNS NetworkPolicy enforcement": "passed",
        "Gateway pod-loss recovery": "passed",
        "Node drain": "passed",
        "Gateway rolling deployment": "passed",
        "Gateway rollback": "passed",
        "Routed HTTP convergence": "passed",
        "WebSocket handshake": "passed",
        "WebSocket capacity": "passed",
        "WebSocket drain": "passed",
        "Telemetry exporter stall": "passed",
    }
    fields.update(overrides)
    sustained_load = sustained_load or load_output()
    return (
        "# Kubernetes qualification report\n\n"
        + "\n".join(f"- {key}: {value}" for key, value in fields.items())
        + "\n\n## Sustained operational and routed HTTP load\n\n"
        + "```text\n"
        + sustained_load
        + "\n```\n"
    )


class QualificationReportContractTest(unittest.TestCase):
    def write_report(self, content):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "qualification.md"
        path.write_text(content)
        self.addCleanup(directory.cleanup)
        return path

    def test_complete_report_is_accepted(self):
        self.assertEqual(validate_report(self.write_report(report()), IMAGE), [])

    def test_digest_mismatch_is_rejected(self):
        errors = validate_report(self.write_report(report()), IMAGE.replace("a", "c"))

        self.assertIn("Qualification image does not match the expected image digest", errors)

    def test_missing_fault_evidence_is_rejected(self):
        errors = validate_report(self.write_report(report(**{"Redis recovery": "not run"})), IMAGE)

        self.assertIn("Redis recovery must be passed", errors)

    def test_blank_required_values_are_rejected(self):
        for field in ("Cassandra image", "Redis image", "Load duration", "Load interval", "Load p95 limit", "Recovery time limit", "Load endpoints"):
            with self.subTest(field=field):
                errors = validate_report(self.write_report(report(**{field: ""})), IMAGE)

                self.assertTrue(any(field in error for error in errors))

    def test_malformed_or_non_positive_thresholds_are_rejected(self):
        for field, value in (
            ("Load duration", "30"),
            ("Load interval", "0s"),
            ("Load p95 limit", "fast"),
            ("Recovery time limit", "-1s"),
        ):
            with self.subTest(field=field):
                errors = validate_report(self.write_report(report(**{field: value})), IMAGE)

                self.assertTrue(any(field in error for error in errors))

    def test_missing_sustained_load_results_are_rejected(self):
        errors = validate_report(
            self.write_report(report(sustained_load="not run")), IMAGE
        )

        self.assertIn("sustained-load results are missing or marked not run", errors)

    def test_failed_requests_are_rejected(self):
        errors = validate_report(
            self.write_report(
                report(
                    sustained_load=load_output(
                        **{
                            "/q/metrics": {
                                "total": 10,
                                "success": 9,
                                "failed": 1,
                                "p50": "0.001",
                                "p95": "0.002",
                                "p99": "0.003",
                            }
                        }
                    )
                )
            ),
            IMAGE,
        )

        self.assertIn("sustained-load endpoint /q/metrics contains failed requests", errors)

    def test_p95_above_recorded_limit_is_rejected(self):
        errors = validate_report(
            self.write_report(
                report(
                    sustained_load=load_output(
                        **{
                            "/q/health/live": {
                                "total": 10,
                                "success": 10,
                                "failed": 0,
                                "p50": "0.001",
                                "p95": "1.1",
                                "p99": "1.2",
                            }
                        }
                    )
                )
            ),
            IMAGE,
        )

        self.assertIn("sustained-load endpoint /q/health/live exceeds the p95 limit", errors)

    def test_experimental_report_requires_explicit_opt_in(self):
        content = report(
            **{
                "Result": "passed (experimental unreleased image)",
                "Artifact": "experimental unreleased local image",
                "Qualification image": "aussie-qualification:local",
            }
        )
        path = self.write_report(content)

        self.assertTrue(validate_report(path, IMAGE))
        self.assertEqual(
            validate_report(path, "aussie-qualification:local", allow_experimental=True), []
        )


if __name__ == "__main__":
    unittest.main()
