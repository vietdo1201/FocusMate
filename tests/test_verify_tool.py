# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from unittest import mock

from tools import verify


class IdfCommandTest(unittest.TestCase):
    def test_python_entry_point_uses_active_interpreter(self) -> None:
        self.assertEqual(
            verify.idf_command(r"C:\ESP IDF\tools\idf.py", r"C:\Python 3\python.exe"),
            [r"C:\Python 3\python.exe", r"C:\ESP IDF\tools\idf.py"],
        )

    def test_python_suffix_is_case_insensitive(self) -> None:
        self.assertEqual(verify.idf_command("IDF.PY", "python"), ["python", "IDF.PY"])

    def test_executable_or_shim_is_invoked_directly(self) -> None:
        self.assertEqual(verify.idf_command("/opt/esp/idf.py.exe", "python"), ["/opt/esp/idf.py.exe"])

    @mock.patch("tools.verify.shutil.which", return_value=None)
    def test_missing_idf_has_actionable_error(self, _which: mock.Mock) -> None:
        with self.assertRaisesRegex(RuntimeError, "activate ESP-IDF"):
            verify.idf_command()

    @mock.patch("tools.verify.subprocess.run")
    def test_run_propagates_nonzero_exit(self, process_run: mock.Mock) -> None:
        process_run.side_effect = subprocess.CalledProcessError(7, ["idf.py", "build"])
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            verify.run(["idf.py", "build"], Path.cwd())
        self.assertEqual(raised.exception.returncode, 7)
        process_run.assert_called_once_with(["idf.py", "build"], cwd=Path.cwd(), check=True)


if __name__ == "__main__":
    unittest.main()
