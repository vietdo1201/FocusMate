# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Prepare every pinned AI asset required by Android and ESP-IDF builds."""

from __future__ import annotations

import argparse
import gzip
import json
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
import hashlib
from pathlib import Path

try:
    from .verify_model_assets import load_manifest, sha256
except ImportError:  # Direct execution: python tools/bootstrap_assets.py
    from verify_model_assets import load_manifest, sha256


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "firmware" / ".asset-cache"
WEAR_ASSETS = ROOT / "wear" / "app" / "src" / "main" / "assets" / "generated"
WEB_ASSETS = ROOT / "firmware" / "generated_web_assets"
PINNED_MANIFEST = load_manifest()
PACKAGE_VERSION = PINNED_MANIFEST["tasksVisionVersion"]
FILES = {
    name: (metadata["url"], metadata["sha256"])
    for name, metadata in PINNED_MANIFEST["assets"].items()
}


def download(name: str, force: bool) -> Path:
    url, expected = FILES[name]
    destination = CACHE / name
    if force or not destination.exists() or sha256(destination) != expected:
        temporary = destination.with_suffix(destination.suffix + ".download")
        temporary.unlink(missing_ok=True)
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(temporary) != expected:
            temporary.unlink(missing_ok=True)
            raise RuntimeError(f"SHA-256 mismatch for {name}")
        temporary.replace(destination)
    if sha256(destination) != expected:
        raise RuntimeError(f"SHA-256 mismatch for cached {name}")
    return destination


def safe_extract(archive: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with tarfile.open(archive, "r:gz") as package:
        for member in package.getmembers():
            target = (destination / member.name).resolve()
            if root not in target.parents and target != root:
                raise RuntimeError(f"Unsafe archive member: {member.name}")
        package.extractall(destination, filter="data")


def prepare(force: bool) -> None:
    CACHE.mkdir(parents=True, exist_ok=True)
    package = download("tasks-vision-1.0.1.tgz", force)
    pose = download("pose_landmarker_lite.task", force)
    face = download("face_landmarker.task", force)

    WEAR_ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(pose, WEAR_ASSETS / pose.name)
    shutil.copy2(face, WEAR_ASSETS / face.name)

    extracted = CACHE / f"tasks-vision-{PACKAGE_VERSION}"
    bundle = extracted / "package" / "vision_bundle.mjs"
    if force and extracted.exists():
        shutil.rmtree(extracted)
    if not bundle.exists():
        safe_extract(package, extracted)

    if WEB_ASSETS.exists():
        shutil.rmtree(WEB_ASSETS)
    (WEB_ASSETS / "wasm").mkdir(parents=True)
    package_root = extracted / "package"
    shutil.copy2(bundle, WEB_ASSETS / "vision_bundle.mjs")
    # Use one no-SIMD runtime for both MediaPipe probe outcomes. Older Android
    # browsers can reject the SIMD build, while shipping both binaries would
    # exceed the fixed mp_assets partition. The URL mapper in web_assets.cpp
    # serves these verified bytes for both upstream filenames.
    loader = (package_root / "wasm" / "vision_wasm_nosimd_internal.js").read_bytes()
    # Keep third-party loader bytes intact. The FocusMate-owned classic worker
    # bootstrap exposes its global to our module without modifying upstream code.
    (WEB_ASSETS / "wasm" / "vwi.js").write_bytes(loader)
    for name in ("pose_worker.mjs", "pose_worker_bootstrap.js", "pose_classifier.mjs", "yawn_classifier.mjs"):
        shutil.copy2(ROOT / "firmware" / "main" / "web" / name, WEB_ASSETS / name)
    compact_face = CACHE / "face_landmarker_landmarks_only.task"
    subprocess.run(
        [
            sys.executable,
            str(ROOT / "tools" / "compact_face_landmarker.py"),
            str(face),
            str(compact_face),
        ],
        check=True,
    )
    with zipfile.ZipFile(face, "r") as source_bundle, zipfile.ZipFile(compact_face, "r") as compact_bundle:
        compact_members = []
        for name in compact_bundle.namelist():
            source_bytes = source_bundle.read(name)
            compact_bytes = compact_bundle.read(name)
            if source_bytes != compact_bytes:
                raise RuntimeError(f"Compaction changed Face Landmarker member bytes: {name}")
            compact_members.append({
                "name": name,
                "source_sha256": hashlib.sha256(source_bytes).hexdigest(),
                "output_sha256": hashlib.sha256(compact_bytes).hexdigest(),
                "bytes_unchanged": True,
            })
    for source, target in (
        (
            package_root / "wasm" / "vision_wasm_nosimd_internal.wasm",
            WEB_ASSETS / "wasm" / "vwi.wasm.gz",
        ),
        (pose, WEB_ASSETS / "pose_landmarker_lite.task.gz"),
        (compact_face, WEB_ASSETS / "face_landmarker.task.gz"),
    ):
        with source.open("rb") as input_file, target.open("wb") as compressed_file:
            with gzip.GzipFile(filename="", mode="wb", compresslevel=9, fileobj=compressed_file, mtime=0) as output_file:
                shutil.copyfileobj(input_file, output_file)

    generated = []
    for path in sorted(item for item in WEB_ASSETS.rglob("*") if item.is_file()):
        generated.append({
            "path": path.relative_to(WEB_ASSETS).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    manifest = {
        "schema": 1,
        "tasks_vision_version": PACKAGE_VERSION,
        "tasks_vision_package_sha256": FILES[package.name][1],
        "pose_model_sha256": FILES[pose.name][1],
        "face_model_sha256": FILES[face.name][1],
        "face_model_profile": "landmarks-only-v1",
        "face_asset_sha256": sha256(compact_face),
        "face_compaction": {
            "operation": "deterministic_zip_repack_excluding_optional_blendshapes",
            "input_sha256": sha256(face),
            "output_sha256": sha256(compact_face),
            "retained_members": compact_members,
        },
        "loader_source_sha256": sha256(package_root / "wasm" / "vision_wasm_nosimd_internal.js"),
        "loader_modified": False,
        "files": generated,
    }
    (WEB_ASSETS / "asset-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("Prepared verified Android and firmware MediaPipe assets.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true")
    prepare(parser.parse_args().force)
