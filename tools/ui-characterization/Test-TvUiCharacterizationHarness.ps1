[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$orchestratorPath = Join-Path $PSScriptRoot 'Invoke-TvUiCharacterization.ps1'
$compileHelperPath = Join-Path $PSScriptRoot 'Compile-TvUiCharacterizationProbe.ps1'
$collectorPath = Join-Path $PSScriptRoot 'Collect-TvUiSourceFacts.ps1'
$sourceFactsTestPath = Join-Path $PSScriptRoot 'Test-TvUiSourceFacts.ps1'
$analyzerPath = Join-Path $PSScriptRoot 'Analyze-TvUiCharacterization.ps1'
$probePath = Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt'
$staticWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-static.yml'
$deviceWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-device.yml'

function Assert-ContainsLiteral {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Literal,
        [Parameter(Mandatory)][string]$Failure
    )

    if (-not $Text.Contains($Literal, [System.StringComparison]::Ordinal)) {
        throw $Failure
    }
}

foreach ($required in @(
    $orchestratorPath,
    $compileHelperPath,
    $collectorPath,
    $sourceFactsTestPath,
    $analyzerPath,
    $probePath,
    $staticWorkflowPath,
    $deviceWorkflowPath
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required UI characterization component is missing: $required"
    }
}

$orchestrator = Get-Content -LiteralPath $orchestratorPath -Raw
$compileHelper = Get-Content -LiteralPath $compileHelperPath -Raw
$collector = Get-Content -LiteralPath $collectorPath -Raw
$analyzer = Get-Content -LiteralPath $analyzerPath -Raw
$probe = Get-Content -LiteralPath $probePath -Raw
$staticWorkflow = Get-Content -LiteralPath $staticWorkflowPath -Raw
$deviceWorkflow = Get-Content -LiteralPath $deviceWorkflowPath -Raw

foreach ($literal in @(
    '2302c11441c85b8b5752d7f03cc5bc13be8c6d92',
    '515072022d11b218fcb20f43079f94098b3ea973',
    '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9'
)) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization orchestrator must pin immutable comparison ref $literal"
    Assert-ContainsLiteral $staticWorkflow $literal "Static compile matrix must pin immutable comparison ref $literal"
    Assert-ContainsLiteral $collector $literal "Immutable source-fact collector must pin comparison ref $literal"
}

Assert-ContainsLiteral $orchestrator 'MuxTV_TV_CURRENT_API36' `
    'UI characterization must reuse the canonical API36 AVD.'
Assert-ContainsLiteral $analyzer 'MuxTV_TV_CURRENT_API36' `
    'UI characterization analyzer must reject non-canonical AVD evidence.'
$forbiddenAvdTokens = @('MuxTV_UI_', 'MuxTV_720', 'MuxTV_1080', 'MuxTV_CHARACTERIZATION_')
foreach ($token in $forbiddenAvdTokens) {
    foreach ($component in @($orchestrator, $compileHelper, $analyzer, $deviceWorkflow)) {
        if ($component.Contains($token, [System.StringComparison]::Ordinal)) {
            throw "UI characterization contains forbidden AVD identity token: $token"
        }
    }
}

foreach ($literal in @('1920x1080', '1280x720', '213', 'compact-stress')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization is missing display-mode contract: $literal"
}

foreach ($component in @($orchestrator, $compileHelper)) {
    Assert-ContainsLiteral $component 'UiCharacterizationProbeTest.kt' `
        'Characterization must overlay the repository-owned common probe.'
    Assert-ContainsLiteral $component 'Get-FileHash' `
        'Characterization must verify common-probe SHA256 identity.'
    Assert-ContainsLiteral $component 'worktree' `
        'Characterization must isolate immutable refs in Git worktrees.'
}

Assert-ContainsLiteral $staticWorkflow 'fail-fast: false' `
    'Static compatibility must preserve all A/B/C verdicts when one ref fails.'
foreach ($id in @('A', 'B', 'C')) {
    Assert-ContainsLiteral $staticWorkflow "id: $id" "Static compatibility matrix is missing comparison $id."
}
Assert-ContainsLiteral $staticWorkflow 'Compile-TvUiCharacterizationProbe.ps1' `
    'Static compatibility workflow must call the isolated compile helper.'
Assert-ContainsLiteral $staticWorkflow 'Test-TvUiSourceFacts.ps1' `
    'Static admission must execute immutable source-fact verification.'

Assert-ContainsLiteral $orchestrator 'finally' `
    'UI characterization must restore display configuration in a finally block.'
Assert-ContainsLiteral $orchestrator 'wm size reset' `
    'UI characterization must reset display size.'
Assert-ContainsLiteral $orchestrator 'wm density reset' `
    'UI characterization must reset display density.'

foreach ($literal in @('sourceCommit', 'displayWidthPx', 'displayHeightPx', 'displayDensityDpi', 'probeSha256', 'avdName')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization evidence is missing provenance field: $literal"
}

# Probe records raw semantics/layout and uses only framework/test APIs already available on A/B/C.
# Back and Right are characterized as separate native input paths. UiAutomator is forbidden because
# the immutable app/tv androidTest classpath does not own it.
foreach ($literal in @(
    'fetchSemanticsNode',
    'boundsInRoot',
    'sendKeyDownUpSync',
    'KEYCODE_DPAD_LEFT',
    'KEYCODE_DPAD_RIGHT',
    'KEYCODE_BACK',
    'focusAfterBack',
    'backMovedFocusAwayFromRail',
    'contentOriginRestoredAfterBack',
    'uiAutomation.takeScreenshot',
    'printToString'
)) {
    Assert-ContainsLiteral $probe $literal "Common UI probe is missing required characterization primitive: $literal"
}
if ($probe.Contains('androidx.test.uiautomator', [System.StringComparison]::Ordinal) -or
    $probe.Contains('UiDevice', [System.StringComparison]::Ordinal)) {
    throw 'Common UI probe must not depend on UiAutomator or mutate historical build files to add it.'
}

foreach ($literal in @(
    'railItemWidthDp',
    'ExpectedSharedShellShiftDp = 50.0',
    'allRepresentativeRowsMatchExpectedShift',
    'allRepresentativeCandidateRowsMatchB',
    'allRepresentativeOriginsRestoredAfterBack',
    'allEligibleFocusRowsMoveAwayFromRailOnBack',
    'focusContractEligible'
)) {
    Assert-ContainsLiteral $analyzer $literal "Analyzer is missing required geometry/focus contract: $literal"
}

# Source facts must distinguish the actual shared-shell change from runtime geometry observations.
foreach ($literal in @(
    'contentReservationToken',
    'railMode',
    'railLabels',
    'railCollapsedDp',
    'railExpandedDp',
    'focusOutlineDp',
    'screenInsetDp',
    'sectionGapDp',
    'homeCardWidthDp',
    'homeCardHeightDp',
    'heroTitleSp',
    'sectionTitleSp',
    'cardTitleSp',
    'metadataSp',
    'expectedContentOriginShiftDp'
)) {
    Assert-ContainsLiteral $collector $literal "Immutable source-fact collector is missing: $literal"
}

Assert-ContainsLiteral $deviceWorkflow "- '.github/ui-characterization/run.request'" `
    'Device characterization must be gated by the explicit one-shot request marker.'
if ($deviceWorkflow.Contains('workflow_dispatch:', [System.StringComparison]::Ordinal)) {
    throw 'Device characterization must not expose an unproven broad manual dispatch path during U0.'
}
foreach ($literal in @(
    'Collect-TvUiSourceFacts.ps1',
    'Invoke-TvUiCharacterization.ps1',
    'Analyze-TvUiCharacterization.ps1',
    'upload-evidence-with-retry',
    'Reset-SelfHostedAndroidState.ps1',
    'fetch-depth: 0',
    'validatedCompiledParent',
    'HEAD^',
    'triggerOnly'
)) {
    Assert-ContainsLiteral $deviceWorkflow $literal "Device characterization workflow is missing required control: $literal"
}

$productionMutationPatterns = @('src/main/kotlin', 'AppNavigation.kt', 'TvTokens.kt')
foreach ($pattern in $productionMutationPatterns) {
    if ($orchestrator -match ('(?i)(Set-Content|Copy-Item|Move-Item).{0,160}' + [regex]::Escape($pattern))) {
        throw "UI characterization must not mutate production source: $pattern"
    }
}

Write-Host 'TV UI characterization static harness contract passed.'
