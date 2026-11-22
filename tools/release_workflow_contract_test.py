import tempfile
import unittest
from pathlib import Path

from release_workflow_contract import REQUIRED_SNIPPETS, WORKFLOW, violations


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_current_workflow_has_reproducible_cli_artifact_controls(self):
        self.assertEqual(violations(), [])

    def test_missing_control_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "release.yml"
            workflow.write_text("\n".join(snippet for _, snippet in REQUIRED_SNIPPETS[:-1]))
            problems = violations(workflow)
            self.assertEqual(len(problems), 1)
            self.assertIn("missing CLI SBOM certificate upload", problems[0])

    def test_removing_sbom_signing_block_is_rejected(self):
        source = WORKFLOW.read_text()
        start = source.index('          sbom="aussie-cli-${RELEASE_VERSION}.cdx.json"')
        end = source.index("\n\n      - name: Attest CLI build provenance", start)

        with tempfile.TemporaryDirectory() as directory:
            workflow = Path(directory) / "release.yml"
            workflow.write_text(source[:start] + source[end:])
            problems = violations(workflow)

        self.assertTrue(any("CLI SBOM signing target" in problem for problem in problems))
        self.assertTrue(any("CLI SBOM verification signature" in problem for problem in problems))

    def test_removing_sbom_upload_arguments_is_rejected(self):
        original = WORKFLOW.read_text()
        for suffix, description in (
            ("cdx.json", "CLI SBOM upload"),
            ("cdx.json.sha256", "CLI SBOM checksum upload"),
            ("cdx.json.sig", "CLI SBOM signature upload"),
            ("cdx.json.pem", "CLI SBOM certificate upload"),
        ):
            source = original.replace(
                f'            "aussie-cli-${{RELEASE_VERSION}}.{suffix}" \\\n',
                "",
            )

            with tempfile.TemporaryDirectory() as directory:
                workflow = Path(directory) / "release.yml"
                workflow.write_text(source)
                problems = violations(workflow)

            self.assertTrue(any(description in problem for problem in problems))


if __name__ == "__main__":
    unittest.main()
