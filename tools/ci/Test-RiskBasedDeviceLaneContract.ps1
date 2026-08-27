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

# The legacy Windows orchestrator remains a local/manual implementation seam while
# focused/integration workflows are being migrated. It must still expose explicit
# host-validation ownership so duplicate host work cannot be hidden.
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
    -Message "Focused Android TV evidence must not duplicate the ordinary PR Full host validation."
Assert-ContainsOrdinal `
    -Content $integrationWorkflow `
    -Token '-SkipHostValidation' `
    -Message "Integration DeviceMatrix must skip duplicate Full host validation because integration-gate runs Full explicitly first."

# Product device evidence for a public repository is owned by ephemeral GitHub-hosted
# Linux VMs. API26 and API36 run independently so one emulator cannot contaminate the
# other. A stable aggregate job retains the historical required-check name.
foreach ($requiredMatrixRoutingToken in @(
    'pull_request:',
    'tools/android/**',
    'tools/measurements/**',
    'tools/ci/Test-RiskBasedDeviceLaneContract.ps1',
    'tools/ci/Assert-AndroidTestResults.ps1',
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
    -Message "Android TV product matrix must checkout and attribute evidence to the exact PR head, not GITHUB_SHA merge-ref."

foreach ($requiredHostedToken in @(
    'runs-on: ubuntu-latest',
    'fail-fast: false',
    'api: 26',
    'arch: x86',
    'avd: MuxTV_TV_OLD_API26',
    'api: 36',
    'arch: x86_64',
    'avd: MuxTV_TV_CURRENT_API36',
    'target: android-tv',
    'profile: tv_1080p',
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
    '/dev/kvm',
    'Assert-AndroidTestResults.ps1',
    'name: Android TV product device matrix',
    'needs:',
    '- device'
)) {
    Assert-ContainsOrdinal `
        -Content $standaloneMatrixWorkflow `
        -Token $requiredHostedToken `
        -Message "Android TV product matrix is missing hosted execution contract token: $requiredHostedToken"
}

foreach ($forbiddenToken in @(
    'self-hosted',
    'Assert-SelfHostedRunnerPreflight.ps1',
    'Reset-SelfHostedAndroidState.ps1',
    'Invoke-TvDeviceValidation.ps1',
    'Remove-LegacyMuxTvAvds.ps1'
)) {
    Assert-NotContainsOrdinal `
        -Content $standaloneMatrixWorkflow `
        -Token $forbiddenToken `
        -Message "Hosted Android TV product matrix still contains legacy runner ownership token: $forbiddenToken"
}

$avdMatches = [regex]::Matches($standaloneMatrixWorkflow, 'MuxTV_[A-Za-z0-9_]+') |
    ForEach-Object Value |
    Sort-Object -Unique
$expectedAvds = @('MuxTV_TV_CURRENT_API36', 'MuxTV_TV_OLD_API26')
if (@($avdMatches).Count -ne $expectedAvds.Count) {
    throw "Hosted product matrix must reference exactly two repository AVD identities; found: $($avdMatches -join ', ')"
}
foreach ($expectedAvd in $expectedAvds) {
    if ($expectedAvd -notin $avdMatches) {
        throw "Hosted product matrix is missing canonical AVD identity: $expectedAvd"
    }
}

Write-Host "Risk-based Android TV device lane contract is valid for hosted public-repository CI."
