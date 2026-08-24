# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param(
    [string]$OutputPath = (Join-Path $PSScriptRoot "../app/src/main/assets/generated/pose_landmarker_lite.task"),
    [string]$FaceOutputPath = (Join-Path $PSScriptRoot "../app/src/main/assets/generated/face_landmarker.task")
)

$ErrorActionPreference = "Stop"
$modelUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
$expectedSha256 = "59929E1D1EE95287735DDD833B19CF4AC46D29BC7AFDDBBF6753C459690D574A"
$faceModelUrl = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
$faceExpectedSha256 = "64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF"
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
$temporary = "$resolvedOutput.download"
$resolvedFaceOutput = [System.IO.Path]::GetFullPath($FaceOutputPath)
$faceOutputDirectory = Split-Path -Parent $resolvedFaceOutput
$faceTemporary = "$resolvedFaceOutput.download"

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $faceOutputDirectory | Out-Null
try {
    Invoke-WebRequest -UseBasicParsing -Uri $modelUrl -OutFile $temporary
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToUpperInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Pose Landmarker model SHA256 mismatch: expected $expectedSha256, got $actualSha256"
    }
    Move-Item -Force -LiteralPath $temporary -Destination $resolvedOutput
    Write-Output "Verified Pose Landmarker Lite model: $resolvedOutput"
    Invoke-WebRequest -UseBasicParsing -Uri $faceModelUrl -OutFile $faceTemporary
    $actualFaceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $faceTemporary).Hash.ToUpperInvariant()
    if ($actualFaceSha256 -ne $faceExpectedSha256) {
        throw "Face Landmarker model SHA256 mismatch: expected $faceExpectedSha256, got $actualFaceSha256"
    }
    Move-Item -Force -LiteralPath $faceTemporary -Destination $resolvedFaceOutput
    Write-Output "Verified Face Landmarker model: $resolvedFaceOutput"
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -Force -LiteralPath $temporary
    }
    if (Test-Path -LiteralPath $faceTemporary) {
        Remove-Item -Force -LiteralPath $faceTemporary
    }
}
