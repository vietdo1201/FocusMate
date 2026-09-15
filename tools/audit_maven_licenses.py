# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Add only unambiguous, version-specific Maven POM licenses to provenance."""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
PROVENANCE = ROOT / "sbom" / "license-provenance.json"
LOCKFILES = sorted((ROOT / "wear").glob("*/gradle.lockfile"))
RUNTIME_CONFIGURATION = "releaseRuntimeClasspath"
REPOSITORIES = (
    "https://dl.google.com/dl/android/maven2",
    "https://repo.maven.apache.org/maven2",
)


def runtime_coordinates() -> list[tuple[str, str, str]]:
    result: set[tuple[str, str, str]] = set()
    for lockfile in LOCKFILES:
        for line in lockfile.read_text(encoding="utf-8").splitlines():
            if "=" not in line or line.startswith("#"):
                continue
            coordinate, configurations = line.split("=", 1)
            if RUNTIME_CONFIGURATION not in configurations.split(","):
                continue
            parts = coordinate.split(":")
            if len(parts) == 3:
                result.add((parts[0], parts[1], parts[2]))
    return sorted(result)


def pom_urls(group: str, artifact: str, version: str) -> list[str]:
    relative = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    google_first = group.startswith(("androidx.", "com.google.android.", "com.google.firebase", "com.google.mediapipe"))
    repositories = REPOSITORIES if google_first else tuple(reversed(REPOSITORIES))
    return [f"{repository}/{relative}" for repository in repositories]


def fetch_pom(group: str, artifact: str, version: str) -> tuple[str, ET.Element] | None:
    for url in pom_urls(group, artifact, version):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "FocusMate-license-audit/1.0"})
            with urllib.request.urlopen(request, timeout=20) as response:
                return url, ET.fromstring(response.read())
        except (urllib.error.URLError, TimeoutError, ET.ParseError):
            continue
    return None


def child_text(element: ET.Element, name: str) -> str | None:
    child = element.find(f"{{*}}{name}")
    if child is None or child.text is None:
        return None
    return child.text.strip() or None


def pom_licenses(root: ET.Element) -> list[tuple[str, str | None]]:
    licenses = root.find("{*}licenses")
    if licenses is None:
        return []
    return [
        (child_text(row, "name") or "", child_text(row, "url"))
        for row in licenses.findall("{*}license")
    ]


def parent_coordinate(root: ET.Element) -> tuple[str, str, str] | None:
    parent = root.find("{*}parent")
    if parent is None:
        return None
    values = tuple(child_text(parent, key) for key in ("groupId", "artifactId", "version"))
    return values if all(values) else None  # type: ignore[return-value]


def spdx_for_license(name: str, url: str | None) -> str | None:
    value = f"{name} {url or ''}".lower()
    if "apache" in value and ("2.0" in value or "license-2.0" in value or "licenses/license-2.0" in value):
        return "Apache-2.0"
    if "eclipse public license" in value and ("1.0" in value or "epl-v10" in value):
        return "EPL-1.0"
    if "3-clause" in value and "bsd" in value:
        return "BSD-3-Clause"
    if "mit license" in value or "opensource.org/license/mit" in value or "opensource.org/licenses/mit" in value:
        return "MIT"
    return None


def resolve_license(
    group: str,
    artifact: str,
    version: str,
) -> tuple[str, str, str] | None:
    fetched = fetch_pom(group, artifact, version)
    if fetched is None:
        return None
    source, root = fetched
    evidence = "artifact POM licenses/license"
    licenses = pom_licenses(root)
    if not licenses:
        parent = parent_coordinate(root)
        if parent is None:
            return None
        parent_fetched = fetch_pom(*parent)
        if parent_fetched is None:
            return None
        source, parent_root = parent_fetched
        licenses = pom_licenses(parent_root)
        evidence = f"license inherited from Maven parent POM {parent[0]}:{parent[1]}:{parent[2]}"
    identifiers = {spdx_for_license(name, url) for name, url in licenses}
    identifiers.discard(None)
    if len(identifiers) != 1:
        return None
    # Any unrecognized extra license makes the declaration ambiguous.
    if any(spdx_for_license(name, url) is None for name, url in licenses):
        return None
    return identifiers.pop(), source, evidence


def audit(document: dict[str, Any]) -> tuple[int, list[str]]:
    packages = document["packages"]
    added = 0
    unresolved: list[str] = []
    for group, artifact, version in runtime_coordinates():
        purl = f"pkg:maven/{group}/{artifact}@{version}"
        if purl in packages:
            continue
        resolved = resolve_license(group, artifact, version)
        if resolved is None:
            unresolved.append(purl)
            continue
        license_id, source, evidence = resolved
        packages[purl] = {
            "verifiedVersion": version,
            "license": license_id,
            "source": source,
            "evidence": evidence,
        }
        added += 1
    document["packages"] = dict(sorted(packages.items()))
    return added, unresolved


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="Update sbom/license-provenance.json")
    args = parser.parse_args()
    document = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    added, unresolved = audit(document)
    if args.write:
        document["auditedOn"] = date.today().isoformat()
        PROVENANCE.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Resolved {added} new Maven coordinates; unresolved {len(unresolved)}")
    for purl in unresolved:
        print(f"UNRESOLVED {purl}")


if __name__ == "__main__":
    main()
