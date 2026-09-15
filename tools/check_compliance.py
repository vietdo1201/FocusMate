# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Fail closed on licensing, release identity and obvious secret regressions."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = {".c", ".cpp", ".h", ".kt", ".kts", ".mjs", ".js", ".py", ".ps1", ".sh", ".yml", ".yaml", ".xml", ".html", ".properties"}
SOURCE_NAMES = {"CMakeLists.txt", "Kconfig.projbuild"}
SKIP_PARTS = {"build", "managed_components", "generated_web_assets", ".asset-cache"}
SECRET_PATTERNS = {
    "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "GitHub token": re.compile(r"(?:ghp|github_pat)_[A-Za-z0-9_]{20,}"),
    "Google API key": re.compile(r"AIza[0-9A-Za-z_-]{30,}"),
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}
SPDX_LICENSE = "SPDX-License-" + "Identifier:"


def repository_files() -> list[Path]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    )
    return [ROOT / item.decode("utf-8") for item in output.split(b"\0") if item]


errors: list[str] = []
for path in repository_files():
    relative = path.relative_to(ROOT)
    if not path.is_file() or any(part in SKIP_PARTS for part in relative.parts):
        continue
    if path.suffix.lower() in {".jks", ".keystore", ".p12"}:
        errors.append(f"Tracked signing material: {relative}")
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (UnicodeDecodeError, OSError):
        continue
    if path.suffix.lower() in SOURCE_SUFFIXES or path.name in SOURCE_NAMES:
        if SPDX_LICENSE not in "\n".join(text.splitlines()[:20]):
            errors.append(f"Missing SPDX header: {relative}")
    for label, pattern in SECRET_PATTERNS.items():
        if pattern.search(text):
            errors.append(f"Possible {label}: {relative}")

for workflow in (ROOT / ".github" / "workflows").glob("*.yml"):
    for line_number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), 1):
        if "uses:" in line and not re.search(r"@[0-9a-f]{40}(?:\s+#.*)?$", line):
            errors.append(f"Unpinned action: {workflow.relative_to(ROOT)}:{line_number}")

all_text = "\n".join(
    path.read_text(encoding="utf-8-sig", errors="ignore")
    for path in repository_files()
    if path.is_file() and path.suffix.lower() not in {".png", ".jpg", ".jpeg", ".jar"}
)
version_properties = {
    key: value
    for key, value in (
        line.split("=", 1)
        for line in (ROOT / "version.properties").read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    )
}
version_name = version_properties.get("VERSION_NAME")
version_code = version_properties.get("ANDROID_VERSION_CODE")
if ("soucre_code/from_On_Hand_3_" + "android_wear") in all_text:
    errors.append("Stale pre-v2.2.0 Wear path remains")
if version_name != "2.3.0" or version_code != "26":
    errors.append("Candidate version.properties must identify FocusMate 2.3.0 / Android 26")
gradle_text = (ROOT / "wear" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
cmake_text = (ROOT / "firmware" / "CMakeLists.txt").read_text(encoding="utf-8")
if 'focusMateVersion.getProperty("ANDROID_VERSION_CODE")' not in gradle_text or 'focusMateVersion.getProperty("VERSION_NAME")' not in gradle_text:
    errors.append("Android build does not consume the shared version manifest")
if "../version.properties" not in cmake_text or "VERSION_NAME=" not in cmake_text:
    errors.append("Firmware build does not consume the shared version manifest")

sbom = json.loads((ROOT / "sbom" / "focusmate-v2.2.2.spdx.json").read_text(encoding="utf-8"))
project_packages = [item for item in sbom.get("packages", []) if item.get("SPDXID") == "SPDXRef-Package-FocusMate"]
if (
    sbom.get("spdxVersion") != "SPDX-2.3"
    or sbom.get("name") != "FocusMate-v2.2.2"
    or sbom.get("documentNamespace") != "https://github.com/vietdo1201/FocusMate/releases/tag/v2.2.2#spdx"
    or len(project_packages) != 1
    or project_packages[0].get("versionInfo") != "2.2.2"
    or len(sbom.get("packages", [])) < 10
):
    errors.append("SPDX 2.3 SBOM is missing or incomplete")

current_sbom = json.loads((ROOT / "sbom" / "focusmate-current.spdx.json").read_text(encoding="utf-8"))
current_packages = {
    reference["referenceLocator"]: package
    for package in current_sbom.get("packages", [])
    for reference in package.get("externalRefs", [])
    if reference.get("referenceType") == "purl"
}
lock_text = (ROOT / "firmware" / "dependencies.lock").read_text(encoding="utf-8")
locked_firmware = {}
for match in re.finditer(
    r"^  (?P<name>espressif/[^:]+):\n(?P<body>(?: {4}.*\n)+)",
    lock_text,
    flags=re.MULTILINE,
):
    version_match = re.search(r"^ {4}version: ['\"]?(?P<version>[^'\"\r\n]+)", match.group("body"), re.MULTILINE)
    if version_match:
        locked_firmware[match.group("name")] = version_match.group("version")
idf_match = re.search(r"^  idf:\n(?: {4}.*\n)*? {4}version: (?P<version>[^\r\n]+)", lock_text, re.MULTILINE)
if idf_match:
    locked_firmware["espressif/esp-idf"] = idf_match.group("version")
expected_firmware = {
    f"pkg:generic/{name}@{version}"
    for name, version in locked_firmware.items()
}
missing_firmware = sorted(expected_firmware - current_packages.keys())
if len(locked_firmware) != 8 or missing_firmware:
    errors.append(f"Current SBOM does not match all 8 firmware lock entries: {missing_firmware}")
expected_unresolved: set[str] = set()
actual_unresolved = {
    purl
    for purl, package in current_packages.items()
    if package.get("licenseDeclared") == "NOASSERTION"
}
if actual_unresolved != expected_unresolved:
    errors.append(
        "Current SBOM NOASSERTION set changed; audit each exact artifact: "
        f"expected {sorted(expected_unresolved)}, found {sorted(actual_unresolved)}"
    )
expected_model_licenses = {
    "pkg:generic/mediapipe/pose-landmarker-lite-float16@1": "Apache-2.0",
    "pkg:generic/mediapipe/face-landmarker-float16@1": "Apache-2.0",
}
for purl, expected_license in expected_model_licenses.items():
    model_package = current_packages.get(purl)
    if model_package is None or model_package.get("licenseDeclared") != expected_license:
        errors.append(f"Missing verified model license in current SBOM: {purl}")
if "tests/FocusMate_Test/Evidence/ export-ignore" not in (ROOT / ".gitattributes").read_text(encoding="utf-8"):
    errors.append("Binary test evidence is not excluded from source archives")
battery = ROOT / "reports" / "assets" / "2026-08-25-galaxy-watch5-pro-battery-usage.png"
if hashlib.sha256(battery.read_bytes()).hexdigest().upper() != "1C5029065197E50F28A133518701CD92EB6F77323BAFE7F6679E0758864EC26E":
    errors.append("Battery evidence image hash changed")

if errors:
    raise SystemExit("Compliance check failed:\n- " + "\n- ".join(errors))
print("Compliance, SPDX, release identity and secret-pattern checks passed.")
