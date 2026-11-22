import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from release_contract import validate_evidence


class ReleaseContractTest(unittest.TestCase):

    def write_fixture(self, root: Path, **changes: object) -> Path:
        sbom = root / "aussie.cdx.json"
        sbom.write_text('{"bomFormat":"CycloneDX"}\n')
        (root / "aussie.cdx.json.sig").write_text("signature\n")
        (root / "aussie.cdx.json.pem").write_text("certificate\n")
        evidence = {
            "version": "v1.2.3",
            "commit": "a" * 40,
            "image": "ghcr.io/example/aussie:v1.2.3",
            "digest": "sha256:" + "b" * 64,
            "sbom": sbom.name,
            "sbom_sha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
            "sbom_signature": "aussie.cdx.json.sig",
            "sbom_certificate": "aussie.cdx.json.pem",
        }
        evidence.update(changes)
        path = root / "evidence.json"
        path.write_text(json.dumps(evidence))
        return path

    def test_valid_manifest_matches_sbom(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(validate_evidence(self.write_fixture(root), root), [])

    def test_sbom_checksum_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = self.write_fixture(root, sbom_sha256="0" * 64)
            self.assertEqual(
                validate_evidence(evidence, root),
                ["sbom_sha256 does not match the referenced SBOM"],
            )

    def test_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = self.write_fixture(root, sbom="../outside.json")
            self.assertEqual(
                validate_evidence(evidence, root),
                ["sbom must remain within the evidence root"],
            )

    def test_invalid_image_digest_and_commit_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = self.write_fixture(root, commit="short", digest="latest")
            self.assertEqual(
                validate_evidence(evidence, root),
                [
                    "commit must be a 40-character hexadecimal SHA",
                    "digest must be a sha256 image digest",
                ],
            )

    def test_missing_sbom_signature_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = self.write_fixture(root, sbom_signature="missing.sig")
            self.assertEqual(
                validate_evidence(evidence, root),
                ["sbom_signature does not exist: missing.sig"],
            )

    def test_sbom_signature_path_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = self.write_fixture(root, sbom_certificate="../certificate.pem")
            self.assertEqual(
                validate_evidence(evidence, root),
                ["sbom_certificate must remain within the evidence root"],
            )


if __name__ == "__main__":
    unittest.main()
