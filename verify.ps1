# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
if (-not (Get-Command idf.py -ErrorAction SilentlyContinue)) {
    $localIdf = Join-Path $env:USERPROFILE 'esp/esp-idf-v5.5.5/export.ps1'
    if (Test-Path -LiteralPath $localIdf) { & $localIdf }
}
python (Join-Path $PSScriptRoot 'tools/verify.py')
if ($LASTEXITCODE -ne 0) { throw "FocusMate verification failed with exit code $LASTEXITCODE" }
