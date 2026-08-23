[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$orchestratorPath = Join-Path $PSScriptRoot 'Invoke-TvUiCharacterization.ps1'
$compileHelperPath = Join-Path $PSScriptRoot 'Compile-TvUiCharacterizationProbe.ps1'
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
$analyzer = Get-Content -LiteralPath $analyzerPath -Raw
$probe = Get-Content -LiteralPath $probePath -Raw
$staticWorkflow = Get-Content -LiteralPath $staticWorkflowPath -Raw
$deviceWorkflow = Get-Content -LiteralPath $deviceWorkflowPath -Raw

# Immutable comparison points. C may be explicitly overridden at invocation time, but the
# repository default stays pinned to the reviewed #180 head until a deliberate U0 decision moves it.
foreach ($literal in @(
    '2302c11441c85b8b5752d7f03cc5bc13be8c6d92',
    '515072022d11b218fcb20f43079f94098b3ea973',
    '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9'
)) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization orchestrator must pin immutable comparison ref $literal"
    Assert-ContainsLiteral $staticWorkflow $literal "Static compile matrix must pin immutable comparison ref $literal"
}

# U0 owns exactly one repository AVD identity. Resolution modes are configurations of the same
# canonical API36 AVD, never additional emulator identities.
Assert-ContainsLiteral $orchestrator 'MuxTV_TV_CURRENT_API36' `
    'UI characterization must reuse the canonical API36 AVD.'
Assert-ContainsLiteral $analyzer 'MuxTV_TV_CURRENT_API36' `
    'UI characterization analyzer must reject non-canonical AVD evidence.'
$forbiddenAvdTokens = @(
    'MuxTV_UI_',
    'MuxTV_720',
    'MuxTV_1080',
    'MuxTV_CHARACTERIZATION_'
)
foreach ($token in $forbiddenAvdTokens) {
    foreach ($component in @($orchestrator, $compileHelper, $analyzer, $deviceWorkflow)) {
        if ($component.Contains($token, [System.StringComparison]::Ordinal)) {
            throw "UI characterization contains forbidden AVD identity token: $token"
        }
    }
}

# Representative TV modes stay distinct from the compact 320-dpi stress case.
foreach ($literal in @('1920x1080', '1280x720', '213', 'compact-stress')) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization is missing display-mode contract: $literal"
}

# A/B/C receive byte-identical probe source. Historical native tests are not evidence inputs.
foreach ($component in @($orchestrator, $compileHelper)) {
    Assert-ContainsLiteral $component 'UiCharacterizationProbeTest.kt' `
        'Characterization must overlay the repository-owned common probe.'
    Assert-ContainsLiteral $component 'Get-FileHash' `
        'Characterization must verify common-probe SHA256 identity.'
    Assert-ContainsLiteral $component 'worktree' `
        'Characterization must isolate immutable refs in Git worktrees.'
}

# Compile compatibility is independently attributable to A, B and C rather than hidden in one
# monolithic hosted step.
Assert-ContainsLiteral $staticWorkflow 'fail-fast: false' `
    'Static compatibility must preserve all A/B/C verdicts when one ref fails.'
foreach ($id in @('A', 'B', 'C')) {
    Assert-ContainsLiteral $staticWorkflow "id: $id" "Static compatibility matrix is missing comparison $id."
}
Assert-ContainsLiteral $staticWorkflow 'Compile-TvUiCharacterizationProbe.ps1' `
    'Static compatibility workflow must call the isolated compile helper.'

# Display overrides are restored even if instrumentation/build/pull fails.
Assert-ContainsLiteral $orchestrator 'finally' `
    'UI characterization must restore display configuration in a finally block.'
Assert-ContainsLiteral $orchestrator 'wm size reset' `
    'UI characterization must reset display size.'
Assert-ContainsLiteral $orchestrator 'wm density reset' `
    'UI characterization must reset display density.'

# Evidence carries enough provenance to compare A/B/C without trusting screenshots alone.
foreach ($literal in @(
    'sourceCommit',
    'displayWidthPx',
    'displayHeightPx',
    'displayDensityDpi',
    'probeSha256',
    'avdName'
)) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization evidence is missing provenance field: $literal"
}

# Probe records raw semantics/layout plus native D-pad behavior. nav-* bounds are item geometry;
# the analyzer intentionally calls them railItemWidth rather than claiming a container width tag
# that production does not expose.
foreach ($literal in @(
    'fetchSemanticsNode',
    'boundsInRoot',
    'UiDevice',
    'KEYCODE_DPAD_LEFT',
    'KEYCODE_DPAD_RIGHT',
    'printToString'
)) {
    Assert-ContainsLiteral $probe $literal "Common UI probe is missing required characterization primitive: $literal"
}
Assert-ContainsLiteral $analyzer 'railItemWidthDp' `
    'Analyzer must label nav-item geometry honestly instead of treating it as rail-container width.'
Assert-ContainsLiteral $analyzer 'ExpectedSharedShellShiftDp = 50.0' `
    'Analyzer must encode the falsifiable A→B +50dp shared-shell hypothesis.'
Assert-ContainsLiteral $analyzer 'allRepresentativeRowsMatchExpectedShift' `
    'Analyzer must summarize the representative A→B shared-shell hypothesis.'
Assert-ContainsLiteral $analyzer 'allRepresentativeCandidateRowsMatchB' `
    'Analyzer must report whether candidate C preserves B content-origin behavior.'

# The expensive lane is marker-triggered only. Merely editing U0 tooling/workflows must not start
# nine device cases; the marker is created only after exact-head static compatibility is GREEN.
Assert-ContainsLiteral $deviceWorkflow "- '.github/ui-characterization/run.request'" `
    'Device characterization must be gated by the explicit one-shot request marker.'
if ($deviceWorkflow.Contains('workflow_dispatch:', [System.StringComparison]::Ordinal)) {
    throw 'Device characterization must not expose an unproven broad manual dispatch path during U0.'
}
foreach ($literal in @(
    'Invoke-TvUiCharacterization.ps1',
    'Analyze-TvUiCharacterization.ps1',
    'upload-evidence-with-retry',
    'Reset-SelfHostedAndroidState.ps1',
    'fetch-depth: 0'
)) {
    Assert-ContainsLiteral $deviceWorkflow $literal "Device characterization workflow is missing required control: $literal"
}

# Characterization remains test/tooling-only. Production sources are never rewritten.
$productionMutationPatterns = @('src/main/kotlin', 'AppNavigation.kt', 'TvTokens.kt')
foreach ($pattern in $productionMutationPatterns) {
    if ($orchestrator -match ('(?i)(Set-Content|Copy-Item|Move-Item).{0,160}' + [regex]::Escape($pattern))) {
        throw "UI characterization must not mutate production source: $pattern"
    }
}

Write-Host 'TV UI characterization static harness contract passed.'
