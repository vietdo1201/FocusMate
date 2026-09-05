# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.generate_sbom import build_document, package, runtime_coordinates


class GenerateSbomTest(unittest.TestCase):
    def test_google_namespace_is_not_license_evidence(self) -> None:
        item = package(
            "com.google.example:unverified",
            "1.0",
            "pkg:maven/com.google.example/unverified@1.0",
            {},
        )
        self.assertEqual(item["licenseDeclared"], "NOASSERTION")
        self.assertNotIn("comment", item)
        self.assertNotIn("packageComment", item)

    def test_verified_license_requires_exact_version_and_records_source(self) -> None:
        purl = "pkg:maven/example/runtime@1.0"
        provenance = {purl: {
            "verifiedVersion": "1.0",
            "license": "Apache-2.0",
            "source": "https://example.invalid/runtime-1.0.pom",
            "evidence": "POM license metadata",
        }}
        verified = package("example:runtime", "1.0", purl, provenance)
        wrong_version = package("example:runtime", "2.0", purl, provenance)
        self.assertEqual(verified["licenseDeclared"], "Apache-2.0")
        self.assertIn("runtime-1.0.pom", verified["comment"])
        self.assertNotIn("packageComment", verified)
        self.assertEqual(wrong_version["licenseDeclared"], "NOASSERTION")

    def test_generated_packages_use_spdx_23_comment_field(self) -> None:
        document = build_document([], {})
        project = document["packages"][0]
        self.assertIn("comment", project)
        self.assertFalse(any("packageComment" in item for item in document["packages"]))

    def test_only_release_runtime_configuration_is_included(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                "example:runtime:1.0=releaseRuntimeClasspath,releaseCompileClasspath\n"
                "com.google.example:test-only:2.0=testRuntimeClasspath,testCompileClasspath\n",
                encoding="utf-8",
            )
            self.assertEqual(runtime_coordinates(lockfile), {("example", "runtime", "1.0")})
            document = build_document([lockfile], {})
            names = {item["name"] for item in document["packages"]}
            self.assertIn("example:runtime", names)
            self.assertNotIn("com.google.example:test-only", names)

    def test_output_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lockfile = Path(directory) / "gradle.lockfile"
            lockfile.write_text(
                "z:runtime:1=releaseRuntimeClasspath\n"
                "a:runtime:2=releaseRuntimeClasspath\n",
                encoding="utf-8",
            )
            first = json.dumps(build_document([lockfile], {}), sort_keys=True)
            second = json.dumps(build_document([lockfile], {}), sort_keys=True)
            self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
