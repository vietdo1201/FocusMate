# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Generate the release SPDX 2.3 SBOM from pinned dependency manifests."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "sbom" / "focusmate-v2.2.0.spdx.json"
PROJECT_ID = "SPDXRef-Package-FocusMate"


def safe_id(value: str) -> str:
    return "SPDXRef-Package-" + re.sub(r"[^A-Za-z0-9.-]", "-", value)


def package(name: str, version: str, purl: str, license_id: str = "NOASSERTION") -> dict:
    return {
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


packages: dict[tuple[str, str], dict] = {}
for lockfile in (ROOT / "wear").glob("*/gradle.lockfile"):
    for line in lockfile.read_text(encoding="utf-8").splitlines():
        coordinate = line.split("=", 1)[0]
        parts = coordinate.split(":")
        if len(parts) != 3 or coordinate == "empty":
            continue
        group, artifact, version = parts
        name = f"{group}:{artifact}"
        license_id = "Apache-2.0" if group.startswith(("androidx.", "com.google.")) else "NOASSERTION"
        packages[(name, version)] = package(name, version, f"pkg:maven/{group}/{artifact}@{version}", license_id)

for name, version, license_id in (
    ("espressif/esp-dl", "3.3.9", "MIT"),
    ("espressif/esp32-camera", "2.1.7", "Apache-2.0"),
    ("espressif/human_face_detect", "0.5.0", "MIT"),
    ("espressif/mdns", "1.9.1", "Apache-2.0"),
    ("@mediapipe/tasks-vision", "1.0.1", "Apache-2.0"),
    ("mediapipe/pose-landmarker-lite-float16", "1", "Apache-2.0"),
    ("mediapipe/face-landmarker-float16", "1", "Apache-2.0"),
):
    packages[(name, version)] = package(name, version, f"pkg:generic/{name}@{version}", license_id)

project = {
    "SPDXID": PROJECT_ID,
    "name": "FocusMate",
    "versionInfo": "2.2.0",
    "downloadLocation": "https://github.com/vietdo1201/FocusMate",
    "filesAnalyzed": False,
    "licenseConcluded": "Apache-2.0 AND MIT",
    "licenseDeclared": "Apache-2.0 AND MIT",
    "copyrightText": "Copyright 2026 vietdo1201",
    "supplier": "Person: vietdo1201",
}
relationships = [{
    "spdxElementId": "SPDXRef-DOCUMENT",
    "relationshipType": "DESCRIBES",
    "relatedSpdxElement": PROJECT_ID,
}]
for dependency in sorted(packages.values(), key=lambda item: item["SPDXID"]):
    relationships.append({
        "spdxElementId": PROJECT_ID,
        "relationshipType": "DEPENDS_ON",
        "relatedSpdxElement": dependency["SPDXID"],
    })

document = {
    "spdxVersion": "SPDX-2.3",
    "dataLicense": "CC0-1.0",
    "SPDXID": "SPDXRef-DOCUMENT",
    "name": "FocusMate-v2.2.0",
    "documentNamespace": "https://github.com/vietdo1201/FocusMate/releases/tag/v2.2.0#spdx",
    "creationInfo": {
        "created": "2026-08-25T00:00:00Z",
        "creators": ["Tool: FocusMate-tools-generate-sbom-1.0", "Person: vietdo1201"],
        "licenseListVersion": "3.25",
    },
    "packages": [project, *sorted(packages.values(), key=lambda item: item["SPDXID"])],
    "relationships": relationships,
}
OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Wrote {OUTPUT.relative_to(ROOT)} with {len(packages) + 1} packages")
