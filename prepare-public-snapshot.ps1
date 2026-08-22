[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$destinationPath = [IO.Path]::GetFullPath($Destination)
$workspacePath = [IO.Path]::GetFullPath($PSScriptRoot)
if ($destinationPath.StartsWith($workspacePath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Destination must be outside the working repository.'
}
if (Test-Path -LiteralPath $destinationPath) {
    if ((Get-ChildItem -LiteralPath $destinationPath -Force | Select-Object -First 1)) {
        throw "Destination is not empty: $destinationPath"
    }
} else {
    New-Item -ItemType Directory -Path $destinationPath | Out-Null
}

$archive = Join-Path ([IO.Path]::GetTempPath()) ("focusmate-public-{0}.zip" -f [Guid]::NewGuid())
try {
    & git -C $workspacePath archive --format=zip --output=$archive HEAD
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
    Expand-Archive -LiteralPath $archive -DestinationPath $destinationPath
}
finally {
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
}

& git -C $destinationPath init -b main
& git -C $destinationPath config user.name 'vietdo1201'
& git -C $destinationPath config user.email '234380247+vietdo1201@users.noreply.github.com'
& git -C $destinationPath add --all
& git -C $destinationPath commit -m 'Initial public release'
if ($LASTEXITCODE -ne 0) { throw 'Creating the sanitized public commit failed.' }

Write-Output "Public snapshot ready: $destinationPath"
Write-Output 'The original repository history remains unchanged.'
