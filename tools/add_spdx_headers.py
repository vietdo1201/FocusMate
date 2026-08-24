# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""Idempotently add Apache-2.0 SPDX headers to project-owned source files."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKIP_PARTS = {".git", ".gradle", ".kotlin", "build", "managed_components", ".asset-cache", "generated_web_assets"}
LINE_PREFIX = {
    ".c": "//", ".cpp": "//", ".h": "//", ".kt": "//", ".kts": "//",
    ".mjs": "//", ".js": "//", ".py": "#", ".ps1": "#", ".sh": "#",
    ".yml": "#", ".yaml": "#", ".properties": "#",
}
SPDX_LICENSE = "SPDX-License-" + "Identifier:"


def header_for(path: Path) -> str | None:
    if path.name == "CMakeLists.txt" or path.name.startswith("Kconfig"):
        prefix = "#"
    else:
        prefix = LINE_PREFIX.get(path.suffix.lower())
    if prefix:
        return (
            f"{prefix} SPDX-FileCopyrightText: 2026 vietdo1201\n"
            f"{prefix} {SPDX_LICENSE} Apache-2.0\n"
        )
    if path.suffix.lower() == ".html":
        return f"<!-- SPDX-FileCopyrightText: 2026 vietdo1201 -->\n<!-- {SPDX_LICENSE} Apache-2.0 -->\n"
    if path.suffix.lower() == ".xml":
        return f"<!-- SPDX-FileCopyrightText: 2026 vietdo1201 -->\n<!-- {SPDX_LICENSE} Apache-2.0 -->\n"
    return None


def add_header(path: Path) -> bool:
    header = header_for(path)
    if header is None:
        return False
    raw = path.read_text(encoding="utf-8-sig")
    if SPDX_LICENSE in raw[:1000]:
        return False
    if path.suffix.lower() == ".xml" and raw.startswith("<?xml"):
        first, separator, rest = raw.partition("\n")
        raw = first + separator + header + rest
    elif path.suffix.lower() == ".sh" and raw.startswith("#!"):
        first, separator, rest = raw.partition("\n")
        raw = first + separator + header + rest
    else:
        raw = header + raw
    path.write_text(raw, encoding="utf-8", newline="\n")
    return True


changed = 0
for candidate in ROOT.rglob("*"):
    if not candidate.is_file() or any(part in SKIP_PARTS for part in candidate.parts):
        continue
    changed += int(add_header(candidate))
print(f"Added SPDX headers to {changed} project-owned source files.")
