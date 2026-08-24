# SPDX-FileCopyrightText: 2026 vietdo1201
# SPDX-License-Identifier: Apache-2.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$required = @(
    'FOCUSMATE_RELEASE_STORE_FILE',
    'FOCUSMATE_RELEASE_STORE_PASSWORD',
    'FOCUSMATE_RELEASE_KEY_ALIAS',
    'FOCUSMATE_RELEASE_KEY_PASSWORD'
)

foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing release signing environment variable: $name"
    }
}

$wearRoot = Join-Path $PSScriptRoot 'wear'
$wrapper = Join-Path $wearRoot 'gradlew.bat'
Push-Location $wearRoot
try {
    & $wrapper --no-daemon clean ':protocol:test' ':app:testDebugUnitTest' ':app:lintDebug' ':app:assembleRelease'
    if ($LASTEXITCODE -ne 0) { throw "Signed release build failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}

$apk = Join-Path $wearRoot 'app/build/outputs/apk/release/app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw "Signed APK was not produced: $apk" }

$sdkRoot = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($sdkRoot)) {
    $localProperties = Join-Path $wearRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $sdkRoot = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':'
            $sdkRoot = $sdkRoot -replace '\\\\', '\'
        }
    }
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -and -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $defaultSdk) { $sdkRoot = $defaultSdk }
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -or -not (Test-Path -LiteralPath $sdkRoot)) {
    throw 'Android SDK was not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or sdk.dir in wear/local.properties.'
}
$apksigner = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Filter 'apksigner.bat' -Recurse -File |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1
if ($null -eq $apksigner) { throw "apksigner.bat was not found under $sdkRoot/build-tools" }

& $apksigner.FullName verify --verbose --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed with exit code $LASTEXITCODE" }

$sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "Signed release APK: $apk"
Write-Output "SHA-256: $sha256"
