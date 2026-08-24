#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v idf.py >/dev/null 2>&1 && [[ -n "${IDF_PATH:-}" && -f "$IDF_PATH/export.sh" ]]; then
  # shellcheck disable=SC1090
  source "$IDF_PATH/export.sh"
fi
python "$repo_root/tools/verify.py"
