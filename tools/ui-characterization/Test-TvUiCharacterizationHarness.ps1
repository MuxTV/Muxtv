[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$orchestratorPath = Join-Path $PSScriptRoot "Invoke-TvUiCharacterization.ps1"
$probePath = Join-Path $PSScriptRoot "probe\UiCharacterizationProbeTest.kt"

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

if (-not (Test-Path -LiteralPath $orchestratorPath -PathType Leaf)) {
    throw "UI characterization orchestrator is missing: $orchestratorPath"
}
if (-not (Test-Path -LiteralPath $probePath -PathType Leaf)) {
    throw "Common UI characterization probe is missing: $probePath"
}

$orchestrator = Get-Content -LiteralPath $orchestratorPath -Raw
$probe = Get-Content -LiteralPath $probePath -Raw

# Immutable comparison points. C may be explicitly overridden at invocation time, but the
# repository default must stay pinned to the reviewed PR #180 head until that candidate moves.
Assert-ContainsLiteral $orchestrator '2302c11441c85b8b5752d7f03cc5bc13be8c6d92' `
    "UI characterization must pin accepted baseline A."
Assert-ContainsLiteral $orchestrator '515072022d11b218fcb20f43079f94098b3ea973' `
    "UI characterization must pin Lounge baseline B."
Assert-ContainsLiteral $orchestrator '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9' `
    "UI characterization must pin the reviewed default candidate C."

# The U0 contract owns exactly one repository AVD identity. Resolution modes are display
# configurations of that same API36 AVD, never additional emulator identities.
Assert-ContainsLiteral $orchestrator 'MuxTV_TV_CURRENT_API36' `
    "UI characterization must reuse the canonical API36 AVD."
$forbiddenAvdTokens = @(
    'MuxTV_UI_',
    'MuxTV_720',
    'MuxTV_1080',
    'MuxTV_CHARACTERIZATION_',
    'MuxTV_TV_OLD_API26'
)
foreach ($token in $forbiddenAvdTokens) {
    if ($orchestrator.Contains($token, [System.StringComparison]::Ordinal)) {
        throw "UI characterization contains forbidden AVD identity token: $token"
    }
}

# Representative modes are explicit and cannot silently collapse 720p TV into the compact
# 320-dpi stress case.
foreach ($literal in @(
    '1920x1080',
    '320',
    '1280x720',
    '213',
    'compact-stress'
)) {
    Assert-ContainsLiteral $orchestrator $literal "UI characterization is missing display-mode contract: $literal"
}

# A/B/C must receive byte-identical probe source. The orchestrator owns copying this one file
# into each detached worktree; it must not use each historical ref's native characterization test.
Assert-ContainsLiteral $orchestrator 'UiCharacterizationProbeTest.kt' `
    "UI characterization must overlay the common probe into each ref."
Assert-ContainsLiteral $orchestrator 'Get-FileHash' `
    "UI characterization must verify common-probe identity."

# Display overrides must be restored even when the test/build fails.
Assert-ContainsLiteral $orchestrator 'finally' `
    "UI characterization must restore display configuration in a finally block."
Assert-ContainsLiteral $orchestrator 'wm size reset' `
    "UI characterization must reset display size."
Assert-ContainsLiteral $orchestrator 'wm density reset' `
    "UI characterization must reset display density."

# Worktrees are an evidence mechanism, not persistent repository state.
Assert-ContainsLiteral $orchestrator 'git worktree add' `
    "UI characterization must run immutable refs in isolated worktrees."
Assert-ContainsLiteral $orchestrator 'git worktree remove' `
    "UI characterization must remove temporary worktrees."

# Evidence must carry enough provenance to compare A/B/C without trusting screenshots alone.
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

# The probe must use Compose semantics/layout data and native D-pad input. Static assertions are
# intentionally API-level rather than implementation-level so U0 can evolve without production UI hooks.
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

# Characterization must remain test/tooling-only. The common probe is stored under tools and
# overlaid into androidTest; production sources are never rewritten by this harness.
$productionMutationPatterns = @(
    'src/main/kotlin',
    'AppNavigation.kt',
    'TvTokens.kt'
)
foreach ($pattern in $productionMutationPatterns) {
    if ($orchestrator -match ('(?i)(Set-Content|Copy-Item|Move-Item).{0,160}' + [regex]::Escape($pattern))) {
        throw "UI characterization must not mutate production source: $pattern"
    }
}

Write-Host "TV UI characterization static harness contract passed."
