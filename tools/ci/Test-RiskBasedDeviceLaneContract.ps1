[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

function Get-RepositoryFileContent {
    param([Parameter(Mandatory)][string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Risk-based CI contract input was not found: $RelativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding utf8
}

function Assert-ContainsOrdinal {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -lt 0) {
        throw $Message
    }
}

function Assert-NotContainsOrdinal {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$Token,
        [Parameter(Mandatory)][string]$Message
    )

    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -ge 0) {
        throw $Message
    }
}

$tvValidation = Get-RepositoryFileContent "tools\android\Invoke-TvDeviceValidation.ps1"
$focusedWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-focused-device.yml"
$integrationWorkflow = Get-RepositoryFileContent ".github\workflows\integration-gate.yml"
$standaloneMatrixWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-product-device-matrix.yml"

Assert-ContainsOrdinal `
    -Content $tvValidation `
    -Token '[switch]$SkipHostValidation' `
    -Message "TV device validation must expose an explicit SkipHostValidation switch."
Assert-ContainsOrdinal `
    -Content $tvValidation `
    -Token 'if (-not $SkipHostValidation)' `
    -Message "TV device validation must gate its internal Full host validation behind SkipHostValidation."
Assert-ContainsOrdinal `
    -Content $focusedWorkflow `
    -Token '-SkipHostValidation' `
    -Message "Focused Android TV PR evidence must skip duplicate Full host validation because the PR Fast lane already ran."
Assert-ContainsOrdinal `
    -Content $integrationWorkflow `
    -Token '-SkipHostValidation' `
    -Message "Integration DeviceMatrix must skip duplicate Full host validation because integration-gate runs Full explicitly first."
Assert-NotContainsOrdinal `
    -Content $standaloneMatrixWorkflow `
    -Token '-SkipHostValidation' `
    -Message "Standalone Android TV product matrix must remain self-contained and preserve host Full validation."

# Device-harness changes are exactly the class of changes that can invalidate the
# API26/API36 execution contract. They must receive automatic exact-head matrix
# evidence rather than relying on a manual workflow_dispatch click.
foreach ($requiredMatrixRoutingToken in @(
    'pull_request:',
    'tools/android/**',
    'tools/measurements/**',
    'tools/ci/Test-RiskBasedDeviceLaneContract.ps1',
    '.github/workflows/android-tv-product-device-matrix.yml'
)) {
    Assert-ContainsOrdinal `
        -Content $standaloneMatrixWorkflow `
        -Token $requiredMatrixRoutingToken `
        -Message "Android TV product matrix is missing required risk-based PR routing token: $requiredMatrixRoutingToken"
}

$exactHeadExpression = "github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha"
Assert-ContainsOrdinal `
    -Content $standaloneMatrixWorkflow `
    -Token $exactHeadExpression `
    -Message "Android TV product matrix must checkout and attribute PR evidence to the exact PR head, not GITHUB_SHA merge-ref."
Assert-ContainsOrdinal `
    -Content $standaloneMatrixWorkflow `
    -Token 'github.head_ref || github.ref_name' `
    -Message "Android TV product matrix must preserve the PR head branch identity in evidence."
Assert-ContainsOrdinal `
    -Content $standaloneMatrixWorkflow `
    -Token 'Remove-LegacyMuxTvAvds.ps1' `
    -Message "Android TV product matrix must record a non-destructive legacy MuxTV AVD inventory before acceptance."
Assert-NotContainsOrdinal `
    -Content $standaloneMatrixWorkflow `
    -Token 'Remove-LegacyMuxTvAvds.ps1 -Apply' `
    -Message "Automatic matrix routing must not delete legacy AVDs before the dry-run candidate set is reviewed."

Write-Host "Risk-based Android TV device lane contract is valid."