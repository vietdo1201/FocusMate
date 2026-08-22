[CmdletBinding()]
param(
    [string]$OutputPath = (Join-Path $PSScriptRoot "../app/src/main/assets/generated/pose_landmarker_lite.task")
)

$ErrorActionPreference = "Stop"
$modelUrl = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
$expectedSha256 = "59929E1D1EE95287735DDD833B19CF4AC46D29BC7AFDDBBF6753C459690D574A"
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
$temporary = "$resolvedOutput.download"

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
try {
    Invoke-WebRequest -UseBasicParsing -Uri $modelUrl -OutFile $temporary
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash.ToUpperInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Pose Landmarker model SHA256 mismatch: expected $expectedSha256, got $actualSha256"
    }
    Move-Item -Force -LiteralPath $temporary -Destination $resolvedOutput
    Write-Output "Verified Pose Landmarker Lite model: $resolvedOutput"
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -Force -LiteralPath $temporary
    }
}
