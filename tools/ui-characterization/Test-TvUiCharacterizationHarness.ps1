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
$resetScriptPath = Join-Path $repositoryRoot 'tools\ci\Reset-SelfHostedAndroidState.ps1'
$staticWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-static.yml'
$deviceWorkflowPath = Join-Path $repositoryRoot '.github\workflows\tv-ui-characterization-device.yml'

function Assert-ContainsLiteral {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$Literal,
        [Parameter(Mandatory)][string]$Failure
    )
    if (-not $Text.Contains($Literal, [System.StringComparison]::Ordinal)) { throw $Failure }
}

foreach ($required in @(
    $orchestratorPath, $compileHelperPath, $collectorPath, $sourceFactsTestPath,
    $analyzerPath, $probePath, $resetScriptPath, $staticWorkflowPath, $deviceWorkflowPath
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
$resetScript = Get-Content -LiteralPath $resetScriptPath -Raw
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

Assert-ContainsLiteral $orchestrator 'MuxTV_TV_CURRENT_API36' 'UI characterization must reuse the canonical API36 AVD.'
Assert-ContainsLiteral $analyzer 'MuxTV_TV_CURRENT_API36' 'UI characterization analyzer must reject non-canonical AVD evidence.'
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
    Assert-ContainsLiteral $component 'UiCharacterizationProbeTest.kt' 'Characterization must overlay the repository-owned common probe.'
    Assert-ContainsLiteral $component 'Get-FileHash' 'Characterization must verify common-probe SHA256 identity.'
    Assert-ContainsLiteral $component 'worktree' 'Characterization must isolate immutable refs in Git worktrees.'
}
Assert-ContainsLiteral $orchestrator "'--project-dir'" 'Runtime characterization must bind Gradle to the immutable comparison worktree.'
Assert-ContainsLiteral $orchestrator '$worktreePath' 'Runtime characterization must pass the immutable worktree as Gradle project-dir.'

Assert-ContainsLiteral $staticWorkflow 'fail-fast: false' 'Static compatibility must preserve all A/B/C verdicts when one ref fails.'
foreach ($id in @('A', 'B', 'C')) {
    Assert-ContainsLiteral $staticWorkflow "id: $id" "Static compatibility matrix is missing comparison $id."
}
Assert-ContainsLiteral $staticWorkflow 'Compile-TvUiCharacterizationProbe.ps1' 'Static compatibility workflow must call the isolated compile helper.'
Assert-ContainsLiteral $staticWorkflow 'Test-TvUiSourceFacts.ps1' 'Static admission must execute immutable source-fact verification.'

foreach ($literal in @('finally', 'wm size reset', 'wm density reset')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization display-reset safety contract is missing: $literal"
}

# A clean canonical AVD does not necessarily have app.muxtv.tv.debug installed before the first
# connectedDebugAndroidTest. The harness must probe package presence and only clear installed state.
foreach ($literal in @('Clear-AppStateIfInstalled', 'shell pm path', 'shell pm clear', 'skipping pre-test pm clear')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization clean-AVD package-state guard is missing: $literal"
}
if ($orchestrator -match '(?m)^\s*&\s*\$tools\.Adb\s+-s\s+\$serial\s+shell\s+pm\s+clear\s+app\.muxtv\.tv\.debug') {
    throw 'UI characterization must not unconditionally pm clear a package before its first install.'
}

foreach ($literal in @('sourceCommit', 'displayWidthPx', 'displayHeightPx', 'displayDensityDpi', 'probeSha256', 'avdName')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization evidence is missing provenance field: $literal"
}

# Common probe stays on the Compose test API already proven by immutable A/B/C tests. In those refs
# collection interactions expose fetchSemanticsNodes() as a member and focus checks use assertIsFocused().
# Newer single-node import extensions and SemanticsConfiguration.getOrNull are explicitly forbidden.
foreach ($literal in @(
    'fetchSemanticsNodes()', 'boundsInRoot', 'assertIsFocused()', 'sendKeyDownUpSync', 'KEYCODE_DPAD_LEFT',
    'KEYCODE_DPAD_RIGHT', 'KEYCODE_BACK', 'focusAfterBack', 'backMovedFocusAwayFromRail',
    'contentOriginRestoredAfterBack', 'uiAutomation.takeScreenshot', 'printToString'
)) {
    Assert-ContainsLiteral $probe $literal "Common UI probe is missing required characterization primitive: $literal"
}
foreach ($forbidden in @(
    'import androidx.compose.ui.test.fetchSemanticsNode',
    'import androidx.compose.ui.test.fetchSemanticsNodes',
    '.fetchSemanticsNode()',
    '.getOrNull(SemanticsProperties.'
)) {
    if ($probe.Contains($forbidden, [System.StringComparison]::Ordinal)) {
        throw "Common UI probe uses a Compose semantics API not available on immutable A/B/C refs: $forbidden"
    }
}
if ($probe.Contains('androidx.test.uiautomator', [System.StringComparison]::Ordinal) -or
    $probe.Contains('UiDevice', [System.StringComparison]::Ordinal)) {
    throw 'Common UI probe must not depend on UiAutomator or mutate historical build files to add it.'
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

Assert-ContainsLiteral $deviceWorkflow "- '.github/ui-characterization/run.request'" 'Device characterization must be gated by the explicit one-shot request marker.'
if ($deviceWorkflow.Contains('workflow_dispatch:', [System.StringComparison]::Ordinal)) {
    throw 'Device characterization must not expose an unproven broad manual dispatch path during U0.'
}
foreach ($literal in @(
    'Collect-TvUiSourceFacts.ps1', 'Invoke-TvUiCharacterization.ps1', 'Analyze-TvUiCharacterization.ps1',
    'upload-evidence-with-retry', 'Reset-SelfHostedAndroidState.ps1', 'fetch-depth: 0',
    'validatedCompiledParent', 'HEAD^', 'triggerOnly'
)) {
    Assert-ContainsLiteral $deviceWorkflow $literal "Device characterization workflow is missing required control: $literal"
}

# Regression contract for the self-hosted cleanup TOCTOU race observed after run 32640877508.
# A process may disappear between enumeration and Stop-Process; that is benign. A still-live process
# that cannot be stopped must remain a hard failure.
Assert-ContainsLiteral $resetScript 'Emulator process exited before cleanup stop completed' 'Runner reset must explicitly tolerate a process that disappears before stop completes.'
Assert-ContainsLiteral $resetScript '$sameProcess.Count -gt 0' 'Runner reset must re-probe the same emulator identity before suppressing a stop error.'

$resetRaceRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('muxtv-reset-race-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $resetRaceRoot | Out-Null
try {
    $goneState = [pscustomobject]@{ Calls = 0 }
    $goneProbe = {
        $goneState.Calls++
        if ($goneState.Calls -eq 1) {
            @([pscustomobject]@{ ProcessName = 'emulator'; Id = 424242 })
        } else {
            @()
        }
    }.GetNewClosure()
    $failingStop = {
        param([object]$Process)
        throw [System.InvalidOperationException]::new("simulated stop race for $($Process.Id)")
    }
    $noopAdb = { param([string]$Command) }
    $noBuilds = { param([string]$ResolvedRepositoryRoot) @() }
    $noopRemoval = { param([string]$Path) }

    & $resetScriptPath `
        -RepositoryRoot $resetRaceRoot `
        -EmulatorProcessProbe $goneProbe `
        -StopEmulatorProcess $failingStop `
        -AdbAction $noopAdb `
        -BuildDirectoryProbe $noBuilds `
        -PathRemoval $noopRemoval

    $liveProbe = { @([pscustomobject]@{ ProcessName = 'emulator'; Id = 424243 }) }
    $liveFailureObserved = $false
    try {
        & $resetScriptPath `
            -RepositoryRoot $resetRaceRoot `
            -EmulatorProcessProbe $liveProbe `
            -StopEmulatorProcess $failingStop `
            -AdbAction $noopAdb `
            -BuildDirectoryProbe $noBuilds `
            -PathRemoval $noopRemoval
    } catch {
        $liveFailureObserved = $true
    }
    if (-not $liveFailureObserved) {
        throw 'Runner reset incorrectly suppressed a stop failure while the emulator process was still present.'
    }
} finally {
    Remove-Item -LiteralPath $resetRaceRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$productionMutationPatterns = @('src/main/kotlin', 'AppNavigation.kt', 'TvTokens.kt')
foreach ($pattern in $productionMutationPatterns) {
    if ($orchestrator -match ('(?i)(Set-Content|Copy-Item|Move-Item).{0,160}' + [regex]::Escape($pattern))) {
        throw "UI characterization must not mutate production source: $pattern"
    }
}

Write-Host 'TV UI characterization static harness contract passed.'