import tempfile
import unittest
from pathlib import Path

from docs_contract import broken_links


class DocumentationContractTest(unittest.TestCase):

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


if __name__ == "__main__":
    unittest.main()
