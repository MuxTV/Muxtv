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
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -lt 0) { throw $Message }
}

function Assert-NotContainsOrdinal {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$Token, [Parameter(Mandatory)][string]$Message)
    if ($Content.IndexOf($Token, [System.StringComparison]::Ordinal) -ge 0) { throw $Message }
}

function Assert-HostedWorkflowNoLegacyRunnerOwnership {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$WorkflowName)
    foreach ($forbiddenToken in @('self-hosted', 'Assert-SelfHostedRunnerPreflight.ps1', 'Reset-SelfHostedAndroidState.ps1', 'Invoke-TvDeviceValidation.ps1', 'Remove-LegacyMuxTvAvds.ps1')) {
        Assert-NotContainsOrdinal -Content $Content -Token $forbiddenToken -Message "$WorkflowName still contains legacy runner ownership token: $forbiddenToken"
    }
}

function Assert-HostedEmulatorEntrypoint {
    param([Parameter(Mandatory)][string]$Content, [Parameter(Mandatory)][string]$WorkflowName, [Parameter(Mandatory)][string]$Entrypoint)
    foreach ($requiredToken in @(
        'run: bash ./tools/ci/Enable-HostedAndroidKvm.sh',
        'uses: ./.github/actions/run-hosted-android-tv',
        "entrypoint: $Entrypoint",
        'uses: ./.github/actions/setup-muxtv-jdks'
    )) {
        Assert-ContainsOrdinal -Content $Content -Token $requiredToken -Message "$WorkflowName is missing hosted Android entrypoint token: $requiredToken"
    }
    foreach ($forbiddenToken in @(
        'uses: ReactiveCircus/android-emulator-runner@',
        'script: |',
        'mapfile -t avds',
        '< <(avdmanager list avd -c'
    )) {
        Assert-NotContainsOrdinal -Content $Content -Token $forbiddenToken -Message "$WorkflowName bypasses repository-owned hosted Android execution: $forbiddenToken"
    }
}

$focusedWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-focused-device.yml"
$integrationWorkflow = Get-RepositoryFileContent ".github\workflows\integration-gate.yml"
$standaloneMatrixWorkflow = Get-RepositoryFileContent ".github\workflows\android-tv-product-device-matrix.yml"
$databaseWorkflow = Get-RepositoryFileContent ".github\workflows\database-migration-device-matrix.yml"
$benchmarkWorkflow = Get-RepositoryFileContent ".github\workflows\benchmark-foundation.yml"
$kvmHelper = Get-RepositoryFileContent "tools\ci\Enable-HostedAndroidKvm.sh"
$productEntrypoint = Get-RepositoryFileContent "tools\ci\Run-HostedAndroidProductTests.sh"
$databaseEntrypoint = Get-RepositoryFileContent "tools\ci\Run-HostedDatabaseMigrationTests.sh"
$benchmarkEntrypoint = Get-RepositoryFileContent "tools\ci\Run-HostedMacrobenchmarkDryRun.sh"

foreach ($requiredKvmToken in @(
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    'if [[ ! -c /dev/kvm ]]',
    'udevadm trigger --name-match=kvm',
    'final /dev/kvm checks remain authoritative',
    'sudo chmod 0666 /dev/kvm',
    'if [[ ! -c /dev/kvm ]]',
    'if [[ ! -r /dev/kvm ]]',
    'if [[ ! -w /dev/kvm ]]',
    'kvm-preflight.log'
)) {
    Assert-ContainsOrdinal -Content $kvmHelper -Token $requiredKvmToken -Message "Hosted KVM helper is missing fail-closed token: $requiredKvmToken"
}

foreach ($requiredProductToken in @(
    'MUXTV_EXPECTED_AVD',
    'mapfile -t avds',
    ':catalog:importer:connectedDebugAndroidTest',
    ':catalog:refresh:connectedDebugAndroidTest',
    ':core:credentials:connectedDebugAndroidTest',
    ':core:database:connectedDebugAndroidTest',
    ':player:media3:connectedDebugAndroidTest',
    ':app:tv:connectedDebugAndroidTest',
    'Assert-AndroidTestResults.ps1'
)) {
    Assert-ContainsOrdinal -Content $productEntrypoint -Token $requiredProductToken -Message "Hosted product entrypoint is missing token: $requiredProductToken"
}
foreach ($requiredDatabaseToken in @(':core:database:connectedDebugAndroidTest', ':catalog:importer:connectedDebugAndroidTest', 'Assert-AndroidTestResults.ps1')) {
    Assert-ContainsOrdinal -Content $databaseEntrypoint -Token $requiredDatabaseToken -Message "Hosted database entrypoint is missing token: $requiredDatabaseToken"
}
foreach ($requiredBenchmarkToken in @(
    ':benchmark:macrobenchmark:connectedBenchmarkReleaseAndroidTest',
    'androidx.benchmark.dryRunMode.enable=true',
    'androidx.benchmark.enabledRules=Macrobenchmark',
    'Macrobenchmark dry-run executed zero non-skipped tests.'
)) {
    Assert-ContainsOrdinal -Content $benchmarkEntrypoint -Token $requiredBenchmarkToken -Message "Hosted benchmark entrypoint is missing token: $requiredBenchmarkToken"
}

foreach ($requiredFocusedToken in @(
    'name: Android TV focused device',
    'runs-on: ubuntu-latest',
    'api-level: 36',
    'arch: x86_64',
    'avd-name: MuxTV_TV_CURRENT_API36',
    'evidence-directory: .work/evidence/hosted-android/focused-api36',
    'Assert-AndroidTestResults.ps1'
)) {
    Assert-ContainsOrdinal -Content $focusedWorkflow -Token $requiredFocusedToken -Message "Focused Android TV workflow is missing hosted API36 contract token: $requiredFocusedToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $focusedWorkflow -WorkflowName 'Focused Android TV workflow'
Assert-HostedEmulatorEntrypoint -Content $focusedWorkflow -WorkflowName 'Focused Android TV workflow' -Entrypoint 'Run-HostedAndroidProductTests.sh'

foreach ($requiredMatrixRoutingToken in @('pull_request:', 'tools/android/**', 'tools/measurements/**', 'tools/ci/Test-RiskBasedDeviceLaneContract.ps1', 'tools/ci/Assert-AndroidTestResults.ps1', '.github/workflows/android-tv-product-device-matrix.yml')) {
    Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $requiredMatrixRoutingToken -Message "Android TV product matrix is missing required risk-based PR routing token: $requiredMatrixRoutingToken"
}
$exactHeadExpression = "github.event_name == 'pull_request' && github.event.pull_request.head.sha || github.sha"
Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $exactHeadExpression -Message "Android TV product matrix must checkout and attribute evidence to the exact PR head, not GITHUB_SHA merge-ref."
foreach ($requiredHostedToken in @(
    'runs-on: ubuntu-latest',
    'fail-fast: false',
    'api: 26',
    'arch: x86',
    'avd: MuxTV_TV_OLD_API26',
    'api: 36',
    'arch: x86_64',
    'avd: MuxTV_TV_CURRENT_API36',
    'name: Android TV product device matrix',
    'needs:',
    '- device'
)) {
    Assert-ContainsOrdinal -Content $standaloneMatrixWorkflow -Token $requiredHostedToken -Message "Android TV product matrix is missing hosted execution contract token: $requiredHostedToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $standaloneMatrixWorkflow -WorkflowName 'Android TV product matrix'
Assert-HostedEmulatorEntrypoint -Content $standaloneMatrixWorkflow -WorkflowName 'Android TV product matrix' -Entrypoint 'Run-HostedAndroidProductTests.sh'

$avdMatches = [regex]::Matches($standaloneMatrixWorkflow, 'MuxTV_[A-Za-z0-9_]+') | ForEach-Object Value | Sort-Object -Unique
$expectedAvds = @('MuxTV_TV_CURRENT_API36', 'MuxTV_TV_OLD_API26')
if (@($avdMatches).Count -ne $expectedAvds.Count) { throw "Hosted product matrix must reference exactly two repository AVD identities; found: $($avdMatches -join ', ')" }
foreach ($expectedAvd in $expectedAvds) { if ($expectedAvd -notin $avdMatches) { throw "Hosted product matrix is missing canonical AVD identity: $expectedAvd" } }

foreach ($requiredIntegrationToken in @('name: Integration host Full', 'runs-on: windows-latest', 'name: Integration Android TV API${{ matrix.api }}', 'api: 26', 'avd: MuxTV_TV_OLD_API26', 'api: 36', 'avd: MuxTV_TV_CURRENT_API36', 'name: Full + Android TV DeviceMatrix', 'HOST_RESULT:', 'DEVICE_RESULT:')) {
    Assert-ContainsOrdinal -Content $integrationWorkflow -Token $requiredIntegrationToken -Message "Integration workflow is missing hosted acceptance token: $requiredIntegrationToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $integrationWorkflow -WorkflowName 'Integration workflow'
Assert-HostedEmulatorEntrypoint -Content $integrationWorkflow -WorkflowName 'Integration workflow' -Entrypoint 'Run-HostedAndroidProductTests.sh'

foreach ($requiredDatabaseWorkflowToken in @('name: Database migration device matrix', 'api: 26', 'avd: MuxTV_TV_OLD_API26', 'api: 36', 'avd: MuxTV_TV_CURRENT_API36')) {
    Assert-ContainsOrdinal -Content $databaseWorkflow -Token $requiredDatabaseWorkflowToken -Message "Database migration workflow is missing hosted contract token: $requiredDatabaseWorkflowToken"
}
Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $databaseWorkflow -WorkflowName 'Database migration workflow'
Assert-HostedEmulatorEntrypoint -Content $databaseWorkflow -WorkflowName 'Database migration workflow' -Entrypoint 'Run-HostedDatabaseMigrationTests.sh'

Assert-HostedWorkflowNoLegacyRunnerOwnership -Content $benchmarkWorkflow -WorkflowName 'Benchmark workflow'
Assert-HostedEmulatorEntrypoint -Content $benchmarkWorkflow -WorkflowName 'Benchmark workflow' -Entrypoint 'Run-HostedMacrobenchmarkDryRun.sh'

Write-Host "Risk-based Android TV device lane contract is valid for hosted public-repository CI with checked-in emulator entrypoints."
