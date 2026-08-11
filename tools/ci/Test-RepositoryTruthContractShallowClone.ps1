[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$head = (& git -C $RepositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Unable to resolve repository HEAD."
}

$temporaryRoot = Join-Path $RepositoryRoot ".work\contract-tests\repository-truth-shallow"
$resolvedWorkRoot = [System.IO.Path]::GetFullPath((Join-Path $RepositoryRoot ".work"))
$resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
if (-not $resolvedTemporaryRoot.StartsWith($resolvedWorkRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Temporary shallow-clone path escaped .work."
}

if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
    Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $resolvedTemporaryRoot | Out-Null

try {
    & git -C $resolvedTemporaryRoot init --quiet
    if ($LASTEXITCODE -ne 0) { throw "Unable to initialize shallow contract repository." }
    & git -C $resolvedTemporaryRoot remote add origin $RepositoryRoot
    if ($LASTEXITCODE -ne 0) { throw "Unable to add shallow contract origin." }
    & git -C $resolvedTemporaryRoot fetch --quiet --depth=1 origin $head
    if ($LASTEXITCODE -ne 0) { throw "Unable to fetch shallow contract HEAD." }
    & git -C $resolvedTemporaryRoot checkout --quiet --detach FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw "Unable to checkout shallow contract HEAD." }

    Copy-Item `
        -LiteralPath (Join-Path $RepositoryRoot "tools\ci\Test-RepositoryTruthContract.ps1") `
        -Destination (Join-Path $resolvedTemporaryRoot "tools\ci\Test-RepositoryTruthContract.ps1") `
        -Force

    & (Join-Path $resolvedTemporaryRoot "tools\ci\Test-RepositoryTruthContract.ps1") -RepositoryRoot $resolvedTemporaryRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Repository truth contract rejected a valid shallow checkout."
    }
} finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}

Write-Host "Repository truth shallow-checkout contract passed."
