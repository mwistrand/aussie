import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import workflow_contract


class WorkflowContractTest(unittest.TestCase):

    def test_repository_workflows_are_currently_pinned(self):
        self.assertEqual(workflow_contract.mutable_action_refs(), [])

    def test_list_item_mutable_reference_is_rejected(self):
        with TemporaryDirectory() as directory:
            workflow = Path(directory) / "workflow.yml"
            workflow.write_text("steps:\n  - uses: owner/action@v4\n")

            violations = workflow_contract.mutable_action_refs(Path(directory))

        self.assertEqual(len(violations), 1)
        self.assertIn("owner/action@v4", violations[0])

    def test_yaml_workflow_mutable_reference_is_rejected(self):
        with TemporaryDirectory() as directory:
            workflow = Path(directory) / "workflow.yaml"
            workflow.write_text("steps:\n  - uses: owner/action@main\n")

            violations = workflow_contract.mutable_action_refs(Path(directory))

        self.assertEqual(len(violations), 1)
        self.assertIn("owner/action@main", violations[0])


if __name__ == "__main__":
    unittest.main()
