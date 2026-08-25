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
if ("soucre_code/from_On_Hand_3_" + "android_wear") in all_text:
    errors.append("Stale pre-v2.2.0 Wear path remains")
if 'versionCode     = 24' not in (ROOT / "wear" / "app" / "build.gradle.kts").read_text(encoding="utf-8"):
    errors.append("Android versionCode is not 24")
if 'versionName     = "2.2.1"' not in (ROOT / "wear" / "app" / "build.gradle.kts").read_text(encoding="utf-8"):
    errors.append("Android versionName is not 2.2.1")
if 'set(PROJECT_VER "2.2.1")' not in (ROOT / "firmware" / "CMakeLists.txt").read_text(encoding="utf-8"):
    errors.append("Firmware version is not 2.2.1")

sbom = json.loads((ROOT / "sbom" / "focusmate-v2.2.1.spdx.json").read_text(encoding="utf-8"))
if sbom.get("spdxVersion") != "SPDX-2.3" or len(sbom.get("packages", [])) < 10:
    errors.append("SPDX 2.3 SBOM is missing or incomplete")
battery = ROOT / "reports" / "assets" / "2026-08-25-galaxy-watch5-pro-battery-usage.png"
if hashlib.sha256(battery.read_bytes()).hexdigest().upper() != "1C5029065197E50F28A133518701CD92EB6F77323BAFE7F6679E0758864EC26E":
    errors.append("Battery evidence image hash changed")

if errors:
    raise SystemExit("Compliance check failed:\n- " + "\n- ".join(errors))
print("Compliance, SPDX, release identity and secret-pattern checks passed.")
