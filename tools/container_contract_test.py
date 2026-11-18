import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import container_contract


class ContainerContractTest(unittest.TestCase):

    def test_release_inputs_are_currently_pinned(self):
        self.assertEqual(container_contract.mutable_image_refs(), [])

    def test_mutable_from_reference_is_rejected(self):
        with TemporaryDirectory() as directory:
            dockerfile = Path(directory) / "Dockerfile.jvm"
            dockerfile.write_text("FROM eclipse-temurin:25-jre\n")
            self.assertEqual(
                container_contract.mutable_image_refs((dockerfile,)),
                [f"{dockerfile}:1: eclipse-temurin:25-jre"],
            )

    def test_lowercase_from_reference_is_rejected(self):
        with TemporaryDirectory() as directory:
            dockerfile = Path(directory) / "Dockerfile.jvm"
            dockerfile.write_text("from eclipse-temurin:25-jre\n")
            self.assertEqual(
                container_contract.mutable_image_refs((dockerfile,)),
                [f"{dockerfile}:1: eclipse-temurin:25-jre"],
            )

    def test_mutable_syntax_reference_is_rejected(self):
        with TemporaryDirectory() as directory:
            dockerfile = Path(directory) / "Dockerfile.jvm"
            dockerfile.write_text("# syntax=docker/dockerfile:1.4\n")
            self.assertEqual(
                container_contract.mutable_image_refs((dockerfile,)),
                [f"{dockerfile}:1: docker/dockerfile:1.4"],
            )

    def test_mutable_service_image_is_rejected(self):
        with TemporaryDirectory() as directory:
            workflow = Path(directory) / "ci.yml"
            workflow.write_text("services:\n  redis:\n    image: redis:7\n")
            self.assertEqual(
                container_contract.mutable_image_refs((workflow,)),
                [f"{workflow}:3: redis:7"],
            )

    def test_mutable_scanner_image_is_rejected(self):
        with TemporaryDirectory() as directory:
            workflow = Path(directory) / "security.yml"
            workflow.write_text("env:\n  TRIVY_IMAGE: ghcr.io/aquasecurity/trivy:latest\n")
            self.assertEqual(
                container_contract.mutable_image_refs((workflow,)),
                [f"{workflow}:2: ghcr.io/aquasecurity/trivy:latest"],
            )
