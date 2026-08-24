# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
"""One reproducible verification entry point for the whole repository."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run(command: list[str], cwd: Path = ROOT) -> None:
    print(f"+ {' '.join(command)}", flush=True)
    subprocess.run(command, cwd=cwd, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-firmware", action="store_true", help="Skip ESP-IDF build")
    parser.add_argument("--no-assets", action="store_true", help="Use already prepared model assets")
    args = parser.parse_args()

    if not args.no_assets:
        run([sys.executable, "tools/bootstrap_assets.py"])
    run([sys.executable, "-m", "unittest", "discover", "-s", "tests", "-p", "test_*.py"])
    node_tests = sorted(str(path.relative_to(ROOT)) for path in (ROOT / "tests").glob("*.test.mjs"))
    run(["node", "--test", *node_tests])
    gradle = str(ROOT / "wear" / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    run([
        gradle,
        "--no-daemon",
        "clean",
        ":protocol:test",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleRelease",
    ], ROOT / "wear")
    if not args.no_firmware:
        if shutil.which("idf.py") is None:
            raise RuntimeError("idf.py is not available; activate ESP-IDF or use the platform wrapper")
        run(["idf.py", "-C", "firmware", "fullclean"])
        run(["idf.py", "-C", "firmware", "build"])


if __name__ == "__main__":
    main()
