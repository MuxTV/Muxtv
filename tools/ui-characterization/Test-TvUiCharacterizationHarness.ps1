[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compileHelperPath = Join-Path $PSScriptRoot 'Compile-TvUiCharacterizationProbe.ps1'
$collectorPath = Join-Path $PSScriptRoot 'Collect-TvUiSourceFacts.ps1'
$sourceFactsTestPath = Join-Path $PSScriptRoot 'Test-TvUiSourceFacts.ps1'
$analyzerPath = Join-Path $PSScriptRoot 'Analyze-TvUiCharacterization.ps1'
$analyzerTestPath = Join-Path $PSScriptRoot 'Test-TvUiCharacterizationAnalyzer.ps1'
$hostedEntrypointPath = Join-Path $PSScriptRoot 'Run-HostedTvUiCharacterization.sh'
$hostedContractPath = Join-Path $PSScriptRoot 'Test-TvUiHostedExecutionContract.ps1'
$probePath = Join-Path $PSScriptRoot 'probe\UiCharacterizationProbeTest.kt'
$staticWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-static.yml'
$deviceWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-device.yml'

function Assert-ContainsLiteral {
    param([string]$Text, [string]$Literal, [string]$Failure)
    if ($Text.IndexOf($Literal, [System.StringComparison]::Ordinal) -lt 0) { throw $Failure }
}

foreach ($required in @(
    $compileHelperPath, $collectorPath, $sourceFactsTestPath, $analyzerPath, $analyzerTestPath,
    $hostedEntrypointPath, $hostedContractPath, $probePath, $staticWorkflowPath, $deviceWorkflowPath
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required hosted UI characterization component is missing: $required"
    }
}

$compileHelper = Get-Content -LiteralPath $compileHelperPath -Raw -Encoding utf8
$collector = Get-Content -LiteralPath $collectorPath -Raw -Encoding utf8
$analyzer = Get-Content -LiteralPath $analyzerPath -Raw -Encoding utf8
$entrypoint = Get-Content -LiteralPath $hostedEntrypointPath -Raw -Encoding utf8
$probe = Get-Content -LiteralPath $probePath -Raw -Encoding utf8
$staticWorkflow = Get-Content -LiteralPath $staticWorkflowPath -Raw -Encoding utf8
$deviceWorkflow = Get-Content -LiteralPath $deviceWorkflowPath -Raw -Encoding utf8

$immutableRefs = @(
    '2302c11441c85b8b5752d7f03cc5bc13be8c6d92',
    '515072022d11b218fcb20f43079f94098b3ea973',
    '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9'
)
foreach ($ref in $immutableRefs) {
    Assert-ContainsLiteral $entrypoint $ref "Hosted U0 entrypoint must pin immutable comparison ref $ref"
    Assert-ContainsLiteral $staticWorkflow $ref "Static compile matrix must pin immutable comparison ref $ref"
    Assert-ContainsLiteral $collector $ref "Source-fact collector must pin immutable comparison ref $ref"
}

foreach ($component in @($entrypoint, $analyzer, $deviceWorkflow)) {
    Assert-ContainsLiteral $component 'MuxTV_TV_CURRENT_API36' 'Hosted U0 must use the canonical API36 AVD identity.'
}
foreach ($forbidden in @('MuxTV_UI_', 'MuxTV_720', 'MuxTV_1080', 'MuxTV_CHARACTERIZATION_')) {
    foreach ($component in @($entrypoint, $compileHelper, $analyzer, $deviceWorkflow)) {
        if ($component.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
            throw "Hosted U0 contains forbidden AVD identity token: $forbidden"
        }
    }
}

foreach ($literal in @('1920x1080', '1280x720', '213', 'compact-stress')) {
    Assert-ContainsLiteral $entrypoint $literal "Hosted U0 is missing display-mode contract: $literal"
}
foreach ($literal in @('wm size reset', 'wm density reset', 'trap cleanup EXIT INT TERM')) {
    Assert-ContainsLiteral $entrypoint $literal "Hosted U0 display/worktree cleanup contract is missing: $literal"
}
foreach ($literal in @('UiCharacterizationProbeTest.kt', 'sha256sum', 'git worktree add --detach', 'connectedDebugAndroidTest', 'adb -s "$ANDROID_SERIAL" pull')) {
    Assert-ContainsLiteral $entrypoint $literal "Hosted U0 is missing characterization primitive: $literal"
}
foreach ($literal in @('UiCharacterizationProbeTest.kt', 'Get-FileHash', 'worktree')) {
    Assert-ContainsLiteral $compileHelper $literal "Static comparison compile is missing byte-identical probe primitive: $literal"
}

Assert-ContainsLiteral $staticWorkflow 'fail-fast: false' 'Static compatibility must preserve all A/B/C compile verdicts.'
Assert-ContainsLiteral $staticWorkflow 'uses: ./.github/actions/setup-muxtv-jdks' 'Static A/B/C compile must expose the repository Java toolchains on hosted Windows.'
foreach ($id in @('A', 'B', 'C')) {
    Assert-ContainsLiteral $staticWorkflow "id: $id" "Static compatibility matrix is missing comparison $id."
}
Assert-ContainsLiteral $staticWorkflow 'Test-TvUiHostedExecutionContract.ps1' 'Static contract must validate hosted U0 ownership.'
Assert-ContainsLiteral $staticWorkflow 'Compile-TvUiCharacterizationProbe.ps1' 'Static workflow must compile the common probe on A/B/C.'
Assert-ContainsLiteral $staticWorkflow 'Test-TvUiSourceFacts.ps1' 'Static workflow must verify immutable source facts.'

foreach ($literal in @(
    'fetchSemanticsNodes()', 'boundsInRoot', 'assertIsFocused()', 'sendKeyDownUpSync', 'KEYCODE_DPAD_LEFT',
    'KEYCODE_DPAD_RIGHT', 'KEYCODE_BACK', 'focusAfterBack', 'backMovedFocusAwayFromRail',
    'contentOriginRestoredAfterBack', 'uiAutomation.takeScreenshot', 'printToString'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe is missing required characterization primitive: $literal"
}

# U0 is characterization, not an assertion suite for the behavior under investigation. If measured
# Back/Right removes the destination or its anchor, that is evidence and must be serialized instead
# of aborting the corpus. The independent Right trace must then re-open the destination through the
# same bounded setup path rather than inheriting Back's terminal state.
foreach ($literal in @(
    'private fun NodeHandle.boundsOrNull(): Rect?',
    'routeSelectedAfterBack',
    'anchorPresentAfterBack',
    'routeSelectedAfterRight',
    'anchorPresentAfterRight',
    'rightSequenceRouteActivation',
    'reopenRouteForRightTrace'
)) {
    Assert-ContainsLiteral $probe $literal "Common U0 probe still aborts instead of recording route/anchor loss: $literal"
}

foreach ($forbidden in @(
    'import androidx.compose.ui.test.fetchSemanticsNode',
    'import androidx.compose.ui.test.fetchSemanticsNodes',
    '.fetchSemanticsNode()',
    '.getOrNull(SemanticsProperties.'
)) {
    if ($probe.IndexOf($forbidden, [System.StringComparison]::Ordinal) -ge 0) {
        throw "Common U0 probe uses a Compose semantics API unavailable on immutable A/B/C refs: $forbidden"
    }
}
if ($probe.IndexOf('androidx.test.uiautomator', [System.StringComparison]::Ordinal) -ge 0 -or
    $probe.IndexOf('UiDevice', [System.StringComparison]::Ordinal) -ge 0) {
    throw 'Common U0 probe must not mutate historical build files to add UiAutomator.'
}

foreach ($literal in @(
    'railItemWidthDp', 'ExpectedSharedShellShiftDp = 50.0', 'allRepresentativeRowsMatchExpectedShift',
    'allRepresentativeCandidateRowsMatchB', 'allRepresentativeOriginsRestoredAfterBack',
    'allEligibleFocusRowsMoveAwayFromRailOnBack', 'focusContractEligible'
)) {
    Assert-ContainsLiteral $analyzer $literal "Analyzer is missing required geometry/focus contract: $literal"
}
foreach ($literal in @(
    'contentReservationToken', 'railMode', 'railLabels', 'railCollapsedDp', 'railExpandedDp',
    'focusOutlineDp', 'screenInsetDp', 'sectionGapDp', 'homeCardWidthDp', 'homeCardHeightDp',
    'heroTitleSp', 'sectionTitleSp', 'cardTitleSp', 'metadataSp', 'expectedContentOriginShiftDp'
)) {
    Assert-ContainsLiteral $collector $literal "Immutable source-fact collector is missing: $literal"
}

foreach ($forbidden in @('src/main/kotlin', 'AppNavigation.kt', 'TvTokens.kt')) {
    if ($entrypoint -match ('(?i)(cp|mv|sed|perl).{0,160}' + [regex]::Escape($forbidden))) {
        throw "Hosted U0 must not mutate production source: $forbidden"
    }
}

Write-Host 'TV UI characterization static harness contract passed for GitHub-hosted U0.'
