# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Create a deterministic Face Landmarker bundle without optional blendshapes."""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


REQUIRED_MEMBERS = (
    "face_detector.tflite",
    "face_landmarks_detector.tflite",
    "geometry_pipeline_metadata_landmarks.binarypb",
)


def compact(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source, "r") as archive, zipfile.ZipFile(
        destination, "w", compression=zipfile.ZIP_STORED
    ) as output:
        members = set(archive.namelist())
        for name in REQUIRED_MEMBERS:
            if name not in members:
                raise RuntimeError(f"Missing required Face Landmarker member: {name}")
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            output.writestr(info, archive.read(name))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    compact(args.source.resolve(), args.destination.resolve())


if __name__ == "__main__":
    main()
