# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.verify_model_assets import verify_models


class ModelAssetVerificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.pose = b"fixture-pose"
        self.face = b"fixture-face"
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(
            json.dumps({
                "schema": 1,
                "assets": {
                    "pose_landmarker_lite.task": {
                        "wearModel": True,
                        "sha256": hashlib.sha256(self.pose).hexdigest(),
                    },
                    "face_landmarker.task": {
                        "wearModel": True,
                        "sha256": hashlib.sha256(self.face).hexdigest(),
                    },
                    "web-only.bin": {"sha256": "0" * 64},
                },
            }),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_valid_fixture_passes(self) -> None:
        (self.assets / "pose_landmarker_lite.task").write_bytes(self.pose)
        (self.assets / "face_landmarker.task").write_bytes(self.face)
        self.assertEqual(verify_models(self.assets, self.manifest), [])

    def test_missing_pose_and_face_are_reported(self) -> None:
        errors = verify_models(self.assets, self.manifest)
        self.assertTrue(any("missing model" in error and "pose_landmarker_lite.task" in error for error in errors))
        self.assertTrue(any("missing model" in error and "face_landmarker.task" in error for error in errors))

    def test_hash_mismatch_is_reported(self) -> None:
        (self.assets / "pose_landmarker_lite.task").write_bytes(b"wrong")
        (self.assets / "face_landmarker.task").write_bytes(self.face)
        errors = verify_models(self.assets, self.manifest)
        self.assertEqual(len(errors), 1)
        self.assertIn("SHA-256 mismatch", errors[0])

    def test_manifest_cannot_drop_a_required_wear_model(self) -> None:
        document = json.loads(self.manifest.read_text(encoding="utf-8"))
        del document["assets"]["face_landmarker.task"]
        self.manifest.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "face_landmarker.task"):
            verify_models(self.assets, self.manifest)


if __name__ == "__main__":
    unittest.main()
