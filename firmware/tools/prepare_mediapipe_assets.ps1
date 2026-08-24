# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
$firmwareRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$generatedRoot = [IO.Path]::GetFullPath((Join-Path $firmwareRoot 'generated_web_assets'))
$cacheRoot = [IO.Path]::GetFullPath((Join-Path $firmwareRoot '.asset-cache'))
$packageVersion = '1.0.1'
$packageSha256 = 'EE318EAA3D42230AA10910D114FAF2A488C577C4E4D33C7CB04126924ACA505F'
$modelSha256 = '59929E1D1EE95287735DDD833B19CF4AC46D29BC7AFDDBBF6753C459690D574A'
$faceModelSha256 = '64184E229B263107BC2B804C6625DB1341FF2BB731874B0BCC2FE6544E0BC9FF'
$packageUrl = 'https://registry.npmjs.org/@mediapipe/tasks-vision/-/tasks-vision-1.0.1.tgz'
$modelUrl = 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task'
$faceModelUrl = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task'

function Assert-ChildPath([string]$Path, [string]$Parent, [string]$ExpectedLeaf) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($resolvedParent, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -ne $ExpectedLeaf) {
        throw "Unsafe generated asset path: $resolved"
    }
}

function Get-VerifiedFile([string]$Url, [string]$Path, [string]$ExpectedSha256) {
    if ($Force -or -not (Test-Path -LiteralPath $Path)) {
        Invoke-WebRequest $Url -OutFile $Path
    }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ne $ExpectedSha256) {
        throw "SHA-256 mismatch for $Path. Expected $ExpectedSha256, got $actual"
    }
}

Assert-ChildPath $generatedRoot $firmwareRoot 'generated_web_assets'
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
$packagePath = Join-Path $cacheRoot "tasks-vision-$packageVersion.tgz"
$modelPath = Join-Path $cacheRoot 'pose_landmarker_lite.task'
$faceModelPath = Join-Path $cacheRoot 'face_landmarker.task'
Get-VerifiedFile $packageUrl $packagePath $packageSha256
Get-VerifiedFile $modelUrl $modelPath $modelSha256
Get-VerifiedFile $faceModelUrl $faceModelPath $faceModelSha256

$extractRoot = Join-Path $cacheRoot "tasks-vision-$packageVersion"
if ($Force -and (Test-Path -LiteralPath $extractRoot)) {
    Assert-ChildPath $extractRoot $cacheRoot "tasks-vision-$packageVersion"
    Remove-Item -LiteralPath $extractRoot -Recurse -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $extractRoot 'package/vision_bundle.mjs'))) {
    New-Item -ItemType Directory -Path $extractRoot -Force | Out-Null
    & tar -xf $packagePath -C $extractRoot
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $packagePath" }
}

if (Test-Path -LiteralPath $generatedRoot) {
    Remove-Item -LiteralPath $generatedRoot -Recurse -Force
}
New-Item -ItemType Directory -Path (Join-Path $generatedRoot 'wasm') -Force | Out-Null

$packageRoot = Join-Path $extractRoot 'package'
Copy-Item -LiteralPath (Join-Path $packageRoot 'vision_bundle.mjs') -Destination $generatedRoot
$wasmLoaderSource = Join-Path $packageRoot 'wasm/vision_wasm_internal.js'
$wasmLoaderTarget = Join-Path $generatedRoot 'wasm/vwi.js'
Copy-Item -LiteralPath $wasmLoaderSource -Destination $wasmLoaderTarget
# MediaPipe 1.0.1 falls back to dynamic import inside a module Worker. In that
# mode its top-level `var ModuleFactory` is module-scoped, while vision_bundle
# expects `self.ModuleFactory`. Export it explicitly after the pinned upstream
# loader so the same verified package works in both classic and module Workers.
Add-Content -LiteralPath $wasmLoaderTarget -Encoding utf8NoBOM -Value "`nglobalThis.ModuleFactory = ModuleFactory;`n"
Copy-Item -LiteralPath (Join-Path $firmwareRoot 'main/web/pose_worker.mjs') -Destination $generatedRoot
Copy-Item -LiteralPath (Join-Path $firmwareRoot 'main/web/pose_worker_bootstrap.js') -Destination $generatedRoot
Copy-Item -LiteralPath (Join-Path $firmwareRoot 'main/web/pose_classifier.mjs') -Destination $generatedRoot
Copy-Item -LiteralPath (Join-Path $firmwareRoot 'main/web/yawn_classifier.mjs') -Destination $generatedRoot

$gzipScript = @'
const fs = require("fs");
const zlib = require("zlib");
const input = fs.readFileSync(process.argv[1]);
const output = zlib.gzipSync(input, {level: 9});
fs.writeFileSync(process.argv[2], output);
'@
$brotliScript = @'
const fs = require("fs");
const zlib = require("zlib");
const input = fs.readFileSync(process.argv[1]);
const output = zlib.brotliCompressSync(input, {params: {[zlib.constants.BROTLI_PARAM_QUALITY]: 11}});
fs.writeFileSync(process.argv[2], output);
'@
$wasmSource = Join-Path $packageRoot 'wasm/vision_wasm_internal.wasm'
$wasmTarget = Join-Path $generatedRoot 'wasm/vwi.wasm.br'
$modelTarget = Join-Path $generatedRoot 'pose_landmarker_lite.task.gz'
$faceModelTarget = Join-Path $generatedRoot 'face_landmarker.task.gz'
& node -e $brotliScript $wasmSource $wasmTarget
if ($LASTEXITCODE -ne 0) { throw 'Cannot Brotli-compress MediaPipe WASM' }
& node -e $gzipScript $modelPath $modelTarget
if ($LASTEXITCODE -ne 0) { throw 'Cannot gzip-compress Pose Landmarker model' }
& node -e $gzipScript $faceModelPath $faceModelTarget
if ($LASTEXITCODE -ne 0) { throw 'Cannot gzip-compress Face Landmarker model' }

$manifest = [ordered]@{
    schema = 1
    tasks_vision_version = $packageVersion
    tasks_vision_package_sha256 = $packageSha256.ToLowerInvariant()
    pose_model_sha256 = $modelSha256.ToLowerInvariant()
    face_model_sha256 = $faceModelSha256.ToLowerInvariant()
    files = @(Get-ChildItem $generatedRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($generatedRoot.Length + 1).Replace('\', '/')
            bytes = $_.Length
            sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $generatedRoot 'asset-manifest.json') -Encoding utf8NoBOM
$totalBytes = (Get-ChildItem $generatedRoot -Recurse -File |
    Where-Object Name -ne 'asset-manifest.json' | Measure-Object -Property Length -Sum).Sum
Write-Host "Prepared MediaPipe web assets: $totalBytes bytes at $generatedRoot"
