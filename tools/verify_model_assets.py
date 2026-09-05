# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Verify hash-pinned model files before Android packaging."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "tools" / "pinned_assets.json"
DEFAULT_ASSET_DIR = ROOT / "wear" / "app" / "src" / "main" / "assets" / "generated"
REQUIRED_WEAR_MODELS = {"pose_landmarker_lite.task", "face_landmarker.task"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path = DEFAULT_MANIFEST) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or not isinstance(manifest.get("assets"), dict):
        raise ValueError(f"Unsupported pinned asset manifest: {path}")
    return manifest


def wear_models(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    models = {
        name: metadata
        for name, metadata in manifest["assets"].items()
        if metadata.get("wearModel") is True
    }
    missing = REQUIRED_WEAR_MODELS.difference(models)
    if missing:
        raise ValueError(f"Pinned asset manifest is missing required Wear models: {', '.join(sorted(missing))}")
    return models


def verify_models(asset_dir: Path, manifest_path: Path = DEFAULT_MANIFEST) -> list[str]:
    errors: list[str] = []
    for name, metadata in sorted(wear_models(load_manifest(manifest_path)).items()):
        path = asset_dir / name
        if not path.is_file():
            errors.append(f"missing model: {path}")
            continue
        expected = metadata["sha256"]
        actual = sha256(path)
        if actual != expected:
            errors.append(f"SHA-256 mismatch for {path}: expected {expected}, got {actual}")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-dir", type=Path, default=DEFAULT_ASSET_DIR)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args()
    errors = verify_models(args.asset_dir, args.manifest)
    if errors:
        raise SystemExit(
            "Wear model verification failed:\n- "
            + "\n- ".join(errors)
            + "\nRun `python tools/bootstrap_assets.py` from the repository root, then rebuild."
        )
    print(f"Verified {len(wear_models(load_manifest(args.manifest)))} Wear model assets in {args.asset_dir}")


if __name__ == "__main__":
    main()
