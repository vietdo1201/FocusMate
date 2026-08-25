# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Prepare every pinned AI asset required by Android and ESP-IDF builds."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "firmware" / ".asset-cache"
WEAR_ASSETS = ROOT / "wear" / "app" / "src" / "main" / "assets" / "generated"
WEB_ASSETS = ROOT / "firmware" / "generated_web_assets"
PACKAGE_VERSION = "1.0.1"
FILES = {
    "tasks-vision-1.0.1.tgz": (
        "https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-1.0.1.tgz",
        "ee318eaa3d42230aa10910d114faf2a488c577c4e4d33c7cb04126924aca505f",
    ),
    "pose_landmarker_lite.task": (
        "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task",
        "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a",
    ),
    "face_landmarker.task": (
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
        "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
    ),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


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


def compress_brotli(source: Path, destination: Path) -> None:
    script = (
        "const fs=require('fs'),z=require('zlib');"
        "const b=fs.readFileSync(process.argv[1]);"
        "fs.writeFileSync(process.argv[2],z.brotliCompressSync(b,{params:{[z.constants.BROTLI_PARAM_QUALITY]:11}}));"
    )
    subprocess.run(["node", "-e", script, str(source), str(destination)], check=True)


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
    (WEB_ASSETS / "wasm" / "vwi.js").write_bytes(
        loader + b"\nglobalThis.ModuleFactory = ModuleFactory;\n"
    )
    for name in ("pose_worker.mjs", "pose_worker_bootstrap.js", "pose_classifier.mjs", "yawn_classifier.mjs"):
        shutil.copy2(ROOT / "firmware" / "main" / "web" / name, WEB_ASSETS / name)
    compress_brotli(
        package_root / "wasm" / "vision_wasm_nosimd_internal.wasm",
        WEB_ASSETS / "wasm" / "vwi.wasm.br",
    )
    for source, target in (
        (pose, WEB_ASSETS / "pose_landmarker_lite.task.gz"),
        (face, WEB_ASSETS / "face_landmarker.task.gz"),
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
