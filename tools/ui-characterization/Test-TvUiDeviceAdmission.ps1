[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$helperPath = Join-Path $PSScriptRoot 'UiCharacterizationDeviceAdmission.ps1'
$workflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-device.yml'
$markerPath = '.github/ui-characterization/run.request'

function Assert-True {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Failure
    )
    if (-not $Condition) { throw $Failure }
}

function Assert-False {
    param(
        [Parameter(Mandatory)][bool]$Condition,
        [Parameter(Mandatory)][string]$Failure
    )
    if ($Condition) { throw $Failure }
}

function Assert-ContainsLiteral {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Literal,
        [Parameter(Mandatory)][string]$Failure
    )
    if (-not $Text.Contains($Literal, [System.StringComparison]::Ordinal)) { throw $Failure }
}

if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
    throw "Hosted UI characterization admission helper is missing: $helperPath"
}
if (-not (Test-Path -LiteralPath $workflowPath -PathType Leaf)) {
    throw "UI characterization device workflow is missing: $workflowPath"
}

. $helperPath
if (-not (Get-Command Resolve-TvUiDeviceAdmissionState -ErrorAction SilentlyContinue)) {
    throw 'Resolve-TvUiDeviceAdmissionState is not defined.'
}

$validatedParent = '1111111111111111111111111111111111111111'
$triggerHead = '2222222222222222222222222222222222222222'

$valid = Resolve-TvUiDeviceAdmissionState `
    -TriggerHead $triggerHead `
    -ParentHead $validatedParent `
    -MarkerContent $validatedParent `
    -ChangedPaths @($markerPath) `
    -MarkerPath $markerPath
Assert-True ([bool]$valid.Allowed) 'Exact marker-only trigger must be admitted.'
Assert-True ([string]$valid.Reason -ceq 'admitted') 'Valid trigger must report reason=admitted.'
Assert-True ([string]$valid.ValidatedCompiledParent -ceq $validatedParent) 'Admission must preserve validated parent provenance.'

$malformed = Resolve-TvUiDeviceAdmissionState `
    -TriggerHead $triggerHead `
    -ParentHead $validatedParent `
    -MarkerContent 'not-a-sha' `
    -ChangedPaths @($markerPath) `
    -MarkerPath $markerPath
Assert-False ([bool]$malformed.Allowed) 'Malformed marker must not be admitted.'
Assert-True ([string]$malformed.Reason -ceq 'invalid-marker-sha') 'Malformed marker reason must be attributable.'

$stale = Resolve-TvUiDeviceAdmissionState `
    -TriggerHead $triggerHead `
    -ParentHead $validatedParent `
    -MarkerContent '3333333333333333333333333333333333333333' `
    -ChangedPaths @($markerPath) `
    -MarkerPath $markerPath
Assert-False ([bool]$stale.Allowed) 'Stale marker must not be admitted.'
Assert-True ([string]$stale.Reason -ceq 'parent-marker-mismatch') 'Stale marker reason must be attributable.'

$extraChange = Resolve-TvUiDeviceAdmissionState `
    -TriggerHead $triggerHead `
    -ParentHead $validatedParent `
    -MarkerContent $validatedParent `
    -ChangedPaths @($markerPath, 'tools/ui-characterization/Unrelated.ps1') `
    -MarkerPath $markerPath
Assert-False ([bool]$extraChange.Allowed) 'Trigger commit with an extra file must not be admitted.'
Assert-True ([string]$extraChange.Reason -ceq 'not-marker-only') 'Extra-change rejection reason must be attributable.'

$ordinaryFollowUp = Resolve-TvUiDeviceAdmissionState `
    -TriggerHead $triggerHead `
    -ParentHead $validatedParent `
    -MarkerContent $validatedParent `
    -ChangedPaths @('tools/ui-characterization/Test-TvUiDeviceAdmission.ps1') `
    -MarkerPath $markerPath
Assert-False ([bool]$ordinaryFollowUp.Allowed) 'Ordinary follow-up commit must not consume the device runner.'
Assert-True ([string]$ordinaryFollowUp.Reason -ceq 'not-marker-only') 'Ordinary follow-up rejection reason must be attributable.'

$workflow = Get-Content -LiteralPath $workflowPath -Raw
foreach ($literal in @(
    'admission:',
    'runs-on: windows-latest',
    'outputs:',
    'allowed:',
    'Resolve-TvUiDeviceAdmissionState',
    'needs: admission',
    "needs.admission.outputs.allowed == 'true'",
    'runs-on: [self-hosted, Windows, X64, muxtv-android, muxtv-device]',
    'triggerOnly = $true',
    'validatedCompiledParent'
)) {
    Assert-ContainsLiteral $workflow $literal "UI characterization device workflow is missing hosted-admission contract: $literal"
}

Write-Host 'TV UI device hosted-admission contract passed.'
