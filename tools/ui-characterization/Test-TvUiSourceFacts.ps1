[CmdletBinding()]
param(
    [string]$OutputPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$collector = Join-Path $PSScriptRoot 'Collect-TvUiSourceFacts.ps1'
$deleteAfterValidation = [string]::IsNullOrWhiteSpace($OutputPath)

$resolvedOutputPath = if ($deleteAfterValidation) {
    $tempBase = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
        [System.IO.Path]::GetTempPath()
    } else {
        $env:RUNNER_TEMP
    }
    Join-Path $tempBase ("muxtv-ui-source-facts-" + [Guid]::NewGuid().ToString('N') + '.json')
} elseif ([System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath
} else {
    Join-Path $repositoryRoot $OutputPath
}

try {
    & $collector -OutputPath $resolvedOutputPath
    if (-not (Test-Path -LiteralPath $resolvedOutputPath -PathType Leaf)) {
        throw 'Immutable UI source-fact collector did not produce JSON evidence.'
    }

    $document = Get-Content -LiteralPath $resolvedOutputPath -Raw -Encoding utf8 | ConvertFrom-Json
    $facts = @($document.comparisons)
    if ($facts.Count -ne 3) { throw "Expected A/B/C source facts, got $($facts.Count)." }
    $byId = @{}
    foreach ($fact in $facts) { $byId[[string]$fact.comparisonId] = $fact }
    foreach ($id in @('A', 'B', 'C')) {
        if (-not $byId.ContainsKey($id)) { throw "Missing source facts for comparison $id." }
    }

    $a = $byId['A']
    $b = $byId['B']
    $c = $byId['C']

    if ([string]$a.contentReservationToken -cne 'railCollapsed') { throw 'A must reserve railCollapsed.' }
    if ([string]$b.contentReservationToken -cne 'railExpanded') { throw 'B must reserve railExpanded.' }
    if ([string]$c.contentReservationToken -cne 'railExpanded') { throw 'C must reserve railExpanded.' }
    if ([string]$a.railMode -cne 'transient-focus-expanded') { throw 'A must use transient focus-expanded rail mode.' }
    if ([string]$b.railMode -cne 'permanent-expanded') { throw 'B must use permanent expanded rail mode.' }
    if ([string]$c.railMode -cne 'permanent-expanded') { throw 'C must use permanent expanded rail mode.' }
    if ([string]$a.railLabels -cne 'focus-dependent') { throw 'A rail labels must be focus-dependent.' }
    if ([string]$b.railLabels -cne 'always-visible') { throw 'B rail labels must be always visible.' }
    if ([string]$c.railLabels -cne 'always-visible') { throw 'C rail labels must be always visible.' }

    $expected = @{
        'A.railCollapsedDp' = 88.0; 'A.railExpandedDp' = 248.0; 'A.focusOutlineDp' = 3.0
        'A.screenInsetDp' = 56.0; 'A.sectionGapDp' = 40.0; 'A.homeCardWidthDp' = 300.0
        'A.homeCardHeightDp' = 140.0; 'A.heroTitleSp' = 48.0; 'A.sectionTitleSp' = 26.0
        'A.cardTitleSp' = 20.0; 'A.metadataSp' = 15.0
        'B.railCollapsedDp' = 88.0; 'B.railExpandedDp' = 138.0; 'B.focusOutlineDp' = 1.0
        'B.screenInsetDp' = 56.0; 'B.sectionGapDp' = 16.0; 'B.homeCardWidthDp' = 120.0
        'B.homeCardHeightDp' = 72.0; 'B.heroTitleSp' = 24.0; 'B.sectionTitleSp' = 14.0
        'B.cardTitleSp' = 10.0; 'B.metadataSp' = 8.0
    }
    foreach ($entry in $expected.GetEnumerator()) {
        $parts = $entry.Key.Split('.')
        $fact = $byId[$parts[0]]
        $actual = [double]$fact.($parts[1])
        if ([math]::Abs($actual - [double]$entry.Value) -gt 0.001) {
            throw "Unexpected immutable source fact $($entry.Key): expected $($entry.Value), got $actual"
        }
    }

    if ([math]::Abs([double]$document.derived.expectedContentOriginShiftDp - 50.0) -gt 0.001) {
        throw "Expected derived A→B shell shift of 50dp, got $($document.derived.expectedContentOriginShiftDp)."
    }
    foreach ($name in @(
        'aUsesCollapsedReservation',
        'bUsesExpandedReservation',
        'cUsesExpandedReservation',
        'aUsesTransientRail',
        'bUsesPermanentRail',
        'cUsesPermanentRail',
        'aLabelsFocusDependent',
        'bLabelsAlwaysVisible',
        'cLabelsAlwaysVisible'
    )) {
        if (-not [bool]$document.derived.$name) { throw "Derived immutable source fact is false: $name" }
    }

    Write-Host "TV UI immutable source-fact contract passed: $resolvedOutputPath"
} finally {
    if ($deleteAfterValidation) {
        Remove-Item -LiteralPath $resolvedOutputPath -Force -ErrorAction SilentlyContinue
    }
}
