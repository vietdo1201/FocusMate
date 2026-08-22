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

$wearRoot = Join-Path $PSScriptRoot 'soucre_code/from_On_Hand_3_android_wear'
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
    throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required to verify the APK signature.'
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
