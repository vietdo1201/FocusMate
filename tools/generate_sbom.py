# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Generate a current, auditable SPDX 2.3 inventory without license guessing."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PROJECT_VERSION = "2.2.2-current-audit"
DEFAULT_OUTPUT = ROOT / "sbom" / "focusmate-current.spdx.json"
DEFAULT_PROVENANCE = ROOT / "sbom" / "license-provenance.json"
PROJECT_ID = "SPDXRef-Package-FocusMate"
RUNTIME_CONFIGURATION = "releaseRuntimeClasspath"


def safe_id(value: str) -> str:
    return "SPDXRef-Package-" + re.sub(r"[^A-Za-z0-9.-]", "-", value)


def read_provenance(path: Path) -> dict[str, dict[str, str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schema") != 1 or not isinstance(document.get("packages"), dict):
        raise ValueError(f"Unsupported license provenance document: {path}")
    return document["packages"]


def resolved_license(
    purl: str,
    version: str,
    provenance: dict[str, dict[str, str]],
) -> tuple[str, str | None]:
    evidence = provenance.get(purl)
    if evidence is None or evidence.get("verifiedVersion") != version:
        return "NOASSERTION", None
    license_id = evidence.get("license", "NOASSERTION")
    source = evidence.get("source")
    if license_id == "NOASSERTION" or not source:
        return "NOASSERTION", None
    note = f"License evidence for {version}: {source} ({evidence.get('evidence', 'metadata')})"
    return license_id, note


def package(
    name: str,
    version: str,
    purl: str,
    provenance: dict[str, dict[str, str]],
) -> dict[str, Any]:
    license_id, evidence_note = resolved_license(purl, version, provenance)
    result: dict[str, Any] = {
        "SPDXID": safe_id(f"{name}-{version}"),
        "name": name,
        "versionInfo": version,
        "downloadLocation": "NOASSERTION",
        "filesAnalyzed": False,
        "licenseConcluded": license_id,
        "licenseDeclared": license_id,
        "copyrightText": "NOASSERTION",
        "externalRefs": [{
            "referenceCategory": "PACKAGE-MANAGER",
            "referenceType": "purl",
            "referenceLocator": purl,
        }],
    }
    if evidence_note is not None:
        result["comment"] = evidence_note
    return result


def runtime_coordinates(lockfile: Path) -> set[tuple[str, str, str]]:
    coordinates: set[tuple[str, str, str]] = set()
    for line in lockfile.read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.startswith("#"):
            continue
        coordinate, configurations = line.split("=", 1)
        if RUNTIME_CONFIGURATION not in configurations.split(","):
            continue
        parts = coordinate.split(":")
        if len(parts) == 3:
            coordinates.add((parts[0], parts[1], parts[2]))
    return coordinates


def build_document(
    lockfiles: list[Path],
    provenance: dict[str, dict[str, str]],
) -> dict[str, Any]:
    packages: dict[tuple[str, str], dict[str, Any]] = {}
    for lockfile in lockfiles:
        for group, artifact, version in runtime_coordinates(lockfile):
            name = f"{group}:{artifact}"
            purl = f"pkg:maven/{group}/{artifact}@{version}"
            packages[(name, version)] = package(name, version, purl, provenance)

    distributed_components = (
        ("espressif/esp-dl", "3.3.9", "pkg:generic/espressif/esp-dl@3.3.9"),
        ("espressif/esp32-camera", "2.1.7", "pkg:generic/espressif/esp32-camera@2.1.7"),
        ("espressif/human_face_detect", "0.5.0", "pkg:generic/espressif/human_face_detect@0.5.0"),
        ("espressif/mdns", "1.9.1", "pkg:generic/espressif/mdns@1.9.1"),
        ("@mediapipe/tasks-vision", "1.0.1", "pkg:npm/%40mediapipe/tasks-vision@1.0.1"),
        ("mediapipe/pose-landmarker-lite-float16", "1", "pkg:generic/mediapipe/pose-landmarker-lite-float16@1"),
        ("mediapipe/face-landmarker-float16", "1", "pkg:generic/mediapipe/face-landmarker-float16@1"),
    )
    for name, version, purl in distributed_components:
        packages[(name, version)] = package(name, version, purl, provenance)

    project = {
        "SPDXID": PROJECT_ID,
        "name": "FocusMate",
        "versionInfo": PROJECT_VERSION,
        "downloadLocation": "https://github.com/vietdo1201/FocusMate",
        "filesAnalyzed": False,
        "licenseConcluded": "Apache-2.0 AND MIT",
        "licenseDeclared": "Apache-2.0 AND MIT",
        "copyrightText": "Copyright 2026 vietdo1201",
        "supplier": "Person: vietdo1201",
        "comment": "Current audit draft; not a replacement for the historical v2.2.2 release SBOM.",
    }
    dependencies = sorted(packages.values(), key=lambda item: item["SPDXID"])
    relationships = [{
        "spdxElementId": "SPDXRef-DOCUMENT",
        "relationshipType": "DESCRIBES",
        "relatedSpdxElement": PROJECT_ID,
    }]
    relationships.extend({
        "spdxElementId": PROJECT_ID,
        "relationshipType": "DEPENDS_ON",
        "relatedSpdxElement": dependency["SPDXID"],
    } for dependency in dependencies)

    return {
        "spdxVersion": "SPDX-2.3",
        "dataLicense": "CC0-1.0",
        "SPDXID": "SPDXRef-DOCUMENT",
        "name": "FocusMate-current-runtime-audit",
        "documentNamespace": "https://github.com/vietdo1201/FocusMate/tree/main#spdx-current-runtime-audit",
        "creationInfo": {
            "created": "2026-09-06T00:00:00Z",
            "creators": ["Tool: FocusMate-tools-generate-sbom-2.0", "Person: vietdo1201"],
            "licenseListVersion": "3.25",
            "comment": (
                "Includes Android releaseRuntimeClasspath plus direct pinned firmware components and AI assets. "
                "The transitive firmware-component license audit is incomplete; unknown licenses remain NOASSERTION."
            ),
        },
        "packages": [project, *dependencies],
        "relationships": relationships,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--provenance", type=Path, default=DEFAULT_PROVENANCE)
    args = parser.parse_args()
    lockfiles = sorted((ROOT / "wear").glob("*/gradle.lockfile"))
    document = build_document(lockfiles, read_provenance(args.provenance))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {args.output.relative_to(ROOT)} with {len(document['packages'])} packages")


if __name__ == "__main__":
    main()
