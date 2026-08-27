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

function Assert-HostedWorkflowNoLegacyRunnerOwnership {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$WorkflowName
    )

    foreach ($forbiddenToken in @(
        'self-hosted',
        'Assert-SelfHostedRunnerPreflight.ps1',
        'Reset-SelfHostedAndroidState.ps1',
        'Invoke-TvDeviceValidation.ps1',
        'Remove-LegacyMuxTvAvds.ps1'
    )) {
        Assert-NotContainsOrdinal -Content $Content -Token $forbiddenToken -Message "$WorkflowName still contains legacy runner ownership token: $forbiddenToken"
    }
}

function Assert-HostedEmulatorScriptPortable {
    param(
        [Parameter(Mandatory)][string]$Content,
        [Parameter(Mandatory)][string]$WorkflowName
    )

    foreach ($requiredToken in @(
        'script: |',
        'set -eu',
        'avd_count=',
        'avd_name=',
        'if [ "$avd_count" -ne 1 ] || [ "$avd_name" != "$MUXTV_EXPECTED_AVD" ]'
    )) {
        Assert-ContainsOrdinal -Content $Content -Token $requiredToken -Message "$WorkflowName is missing POSIX emulator script token: $requiredToken"
    }
    foreach ($forbiddenToken in @(
        'mapfile -t avds',
        '< <(avdmanager list avd -c'
    )) {
        Assert-NotContainsOrdinal -Content $Content -Token $forbiddenToken -Message "$WorkflowName contains bash-only syntax inside android-emulator-runner script: $forbiddenToken"
    }
}

$focusedWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-focused-device.yml"
$integrationWorkflow = Get-RepositoryFileContent ".github\workflows\integration-gate.yml"
$standaloneMatrixWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-product-device-matrix.yml"
$databaseWorkflow = Get-RepositoryFileContent ".github\workflows\database-migration-device-matrix.yml"

foreach ($requiredFocusedToken in @(
    'name: Android TV focused device',
    'runs-on: ubuntu-latest',
    'uses: ./.github/actions/setup-muxtv-jdks',
    'api-level: 36',
    'target: android-tv',
    'arch: x86_64',
    'profile: tv_1080p',
    'avd-name: MuxTV_TV_CURRENT_API36',
    'ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d',
    '/dev/kvm',
    'Assert-AndroidTestResults.ps1'
)) {
    Assert-ContainsOrdinal -Content $focusedWorkflow -Token $requiredFocusedToken -Message "Focused Android TV workflow is missing hosted API36 contract token: $requiredFocusedToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $focusedWorkflow -WorkflowName 'Focused Android TV workflow'
Assert-HostedEmulatorScriptPortable -Content $focusedWorkflow -WorkflowName 'Focused Android TV workflow'

foreach ($requiredMatrixRoutingToken in @(
    'pull_request:',
    'tools/android/**',
    'tools/measurements/**',
    'tools/ci/Test-RiskBasedDeviceLaneContract.ps1',
    'tools/ci/Assert-AndroidTestResults.ps1',
    '.github/workflows/android-tv-product-device-matrix.yml'
)) {
    Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $requiredMatrixRoutingToken -Message "Android TV product matrix is missing required risk-based PR routing token: $requiredMatrixRoutingToken"
}

$exactHeadExpression = "github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha"
Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $exactHeadExpression -Message "Android TV product matrix must checkout and attribute evidence to the exact PR head, not GITHUB_SHA merge-ref."

foreach ($requiredHostedToken in @(
    'runs-on: ubuntu-latest',
    'uses: ./.github/actions/setup-muxtv-jdks',
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
    Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $requiredHostedToken -Message "Android TV product matrix is missing hosted execution contract token: $requiredHostedToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $standaloneMatrixWorkflow -WorkflowName 'Android TV product matrix'
Assert-HostedEmulatorScriptPortable -Content $standaloneMatrixWorkflow -WorkflowName 'Android TV product matrix'

$avdMatches = [regex]::Matches($standaloneMatrixWorkflow, 'MuxTV_[A-Za-z0-9_]+') | ForEach-Object Value | Sort-Object -Unique
$expectedAvds = @('MuxTV_TV_CURRENT_API36', 'MuxTV_TV_OLD_API26')
if (@($avdMatches).Count -ne $expectedAvds.Count) {
    throw "Hosted product matrix must reference exactly two repository AVD identities; found: $($avdMatches -join ', ')"
}
foreach ($expectedAvd in $expectedAvds) {
    if ($expectedAvd -notin $avdMatches) {
        throw "Hosted product matrix is missing canonical AVD identity: $expectedAvd"
    }
}

foreach ($requiredIntegrationToken in @(
    'name: Integration host Full',
    'runs-on: windows-latest',
    'uses: ./.github/actions/setup-muxtv-jdks',
    'name: Integration Android TV API${{ matrix.api }}',
    'api: 26',
    'avd: MuxTV_TV_OLD_API26',
    'api: 36',
    'avd: MuxTV_TV_CURRENT_API36',
    'name: Full + Android TV DeviceMatrix',
    'HOST_RESULT:',
    'DEVICE_RESULT:'
)) {
    Assert-ContainsOrdinal -Content $integrationWorkflow -Token $requiredIntegrationToken -Message "Integration workflow is missing hosted acceptance token: $requiredIntegrationToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $integrationWorkflow -WorkflowName 'Integration workflow'
Assert-HostedEmulatorScriptPortable -Content $integrationWorkflow -WorkflowName 'Integration workflow'

foreach ($requiredDatabaseToken in @(
    'name: Database migration device matrix',
    'api: 26',
    'avd: MuxTV_TV_OLD_API26',
    'api: 36',
    'avd: MuxTV_TV_CURRENT_API36',
    ':core:database:connectedDebugAndroidTest',
    ':catalog:importer:connectedDebugAndroidTest',
    'uses: ./.github/actions/setup-muxtv-jdks'
)) {
    Assert-ContainsOrdinal -Content $databaseWorkflow -Token $requiredDatabaseToken -Message "Database migration workflow is missing hosted contract token: $requiredDatabaseToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $databaseWorkflow -WorkflowName 'Database migration workflow'
Assert-HostedEmulatorScriptPortable -Content $databaseWorkflow -WorkflowName 'Database migration workflow'

Write-Host "Risk-based Android TV device lane contract is valid for hosted public-repository CI with POSIX emulator scripts."
