[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$wearRoot = Join-Path $PSScriptRoot 'soucre_code/from_On_Hand_3_android_wear'
$gradleWrapper = Join-Path $wearRoot 'gradlew.bat'
$tasks = @(
    'clean'
    ':protocol:test'
    ':app:testDebugUnitTest'
    ':app:lintDebug'
    ':app:assembleDebug'
    ':app:assembleRelease'
)

python -m unittest discover -s (Join-Path $PSScriptRoot 'tests') -p 'test_*.py'
if ($LASTEXITCODE -ne 0) {
    throw "Repository contract verification failed with exit code $LASTEXITCODE"
}

node --test (Join-Path $PSScriptRoot 'tests/pose_classifier.test.mjs')
if ($LASTEXITCODE -ne 0) {
    throw "Landmark classifier verification failed with exit code $LASTEXITCODE"
}

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

Push-Location $wearRoot
try {
    & $gradleWrapper --no-daemon $tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Watch rules v2 verification failed with exit code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
