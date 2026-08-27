[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$analyzer = Join-Path $PSScriptRoot 'Analyze-TvUiCharacterization.ps1'
$tempBase = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) {
    [System.IO.Path]::GetTempPath()
} else {
    $env:RUNNER_TEMP
}
$fixtureRoot = Join-Path $tempBase ("muxtv-ui-analyzer-" + [Guid]::NewGuid().ToString('N'))
$probeSha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
$avdName = 'MuxTV_TV_CURRENT_API36'

$comparisons = @(
    [pscustomobject]@{ Id = 'A'; Commit = '2302c11441c85b8b5752d7f03cc5bc13be8c6d92'; OriginDp = 100.0; RailItemWidthDp = 200.0 },
    [pscustomobject]@{ Id = 'B'; Commit = '515072022d11b218fcb20f43079f94098b3ea973'; OriginDp = 150.0; RailItemWidthDp = 90.0 },
    [pscustomobject]@{ Id = 'C'; Commit = '7a45487a0c17d22cda3dd726cdee6d5d7b7f57f9'; OriginDp = 150.0; RailItemWidthDp = 90.0 }
)
$profiles = @(
    [pscustomobject]@{ Id = '1080p-tv'; Width = 1920; Height = 1080; Density = 320; Representative = $true },
    [pscustomobject]@{ Id = '720p-tv'; Width = 1280; Height = 720; Density = 213; Representative = $true },
    [pscustomobject]@{ Id = 'compact-stress'; Width = 1280; Height = 720; Density = 320; Representative = $false }
)

function Convert-DpToPx {
    param(
        [Parameter(Mandatory)][double]$Dp,
        [Parameter(Mandatory)][double]$DensityDpi
    )
    return $Dp * $DensityDpi / 160.0
}

try {
    foreach ($comparison in $comparisons) {
        foreach ($profile in $profiles) {
            $caseDirectory = Join-Path $fixtureRoot "$($comparison.Id)\$($profile.Id)"
            New-Item -ItemType Directory -Force -Path $caseDirectory | Out-Null

            [ordered]@{
                schemaVersion = 1
                sourceCommit = $comparison.Commit
                displayProfile = $profile.Id
                displayLabel = $profile.Id
                displayWidthPx = $profile.Width
                displayHeightPx = $profile.Height
                displayDensityDpi = $profile.Density
                representativeTvMode = [bool]$profile.Representative
                probeSha256 = $probeSha
                avdName = $avdName
                status = 'passed'
                failure = $null
            } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $caseDirectory 'case-manifest.json') -Encoding utf8

            $originPx = Convert-DpToPx -Dp $comparison.OriginDp -DensityDpi $profile.Density
            $railItemWidthPx = Convert-DpToPx -Dp $comparison.RailItemWidthDp -DensityDpi $profile.Density
            $bounds = [ordered]@{
                left = $originPx
                top = 50
                right = $originPx + 300
                bottom = 250
                width = 300
                height = 200
            }
            # compact-stress deliberately models a measured Back/Right route loss. U0 must retain
            # the observation as null bounds + explicit route/anchor state instead of treating it
            # as a malformed or failed characterization case.
            $routeRetained = $profile.Id -ne 'compact-stress'
            $afterInteractionBounds = if ($routeRetained) { $bounds } else { $null }

            $probePath = Join-Path $caseDirectory 'probe-result.json'
            [ordered]@{
                schemaVersion = 2
                sourceCommit = $comparison.Commit
                displayProfile = $profile.Id
                displayWidthPx = $profile.Width
                displayHeightPx = $profile.Height
                displayDensityDpi = $profile.Density
                destinations = @(
                    [ordered]@{
                        destination = 'home'
                        navTag = 'nav-home'
                        routeActivation = 'already-selected'
                        rightSequenceRouteActivation = if ($routeRetained) { 'retained-after-back' } else { 'compose-enter' }
                        routeSelected = $routeRetained
                        routeSelectedAfterBack = $routeRetained
                        anchorPresentAfterBack = $routeRetained
                        routeSelectedAfterRight = $routeRetained
                        anchorPresentAfterRight = $routeRetained
                        anchor = 'tag:home-add-source'
                        anchorHasExplicitFocusAction = $true
                        beforeBounds = $bounds
                        duringRailBounds = $bounds
                        duringBackRailBounds = $bounds
                        duringRightRailBounds = $bounds
                        afterBounds = $afterInteractionBounds
                        afterBackBounds = $afterInteractionBounds
                        afterRightBounds = $afterInteractionBounds
                        railBounds = [ordered]@{ left = 0; top = 100; right = $railItemWidthPx; bottom = 156; width = $railItemWidthPx; height = 56 }
                        focusInitial = 'home-add-source'
                        focusBeforeLeft = 'home-add-source'
                        focusOnRail = 'nav-home'
                        focusBeforeBack = 'nav-home'
                        focusAfterBack = if ($routeRetained) { 'home-add-source' } else { $null }
                        focusBeforeSecondLeft = 'home-add-source'
                        focusOnRailBeforeRight = 'nav-home'
                        focusAfterRight = if ($routeRetained) { 'home-add-source' } else { $null }
                        backReachedExpectedRailItem = $true
                        backMovedFocusAwayFromRail = $true
                        rightReachedExpectedRailItem = $true
                        rightMovedFocusAwayFromRail = $true
                        contentOriginStableDuringRail = $true
                        contentOriginStableDuringBackRail = $true
                        contentOriginRestored = $routeRetained
                        contentOriginRestoredAfterBack = $routeRetained
                        contentOriginRestoredAfterRight = $routeRetained
                    }
                )
            } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $probePath -Encoding utf8

            # Probe serialization legitimately omits a nullable focus owner when no tagged node owns
            # focus after the measured Right event. The analyzer must preserve the measurement instead
            # of treating the absent diagnostic property as a malformed characterization case.
            if ($profile.Id -eq 'compact-stress') {
                $probeFixture = Get-Content -LiteralPath $probePath -Raw | ConvertFrom-Json
                $probeFixture.destinations[0].PSObject.Properties.Remove('focusAfterRight')
                $probeFixture | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $probePath -Encoding utf8
            }
        }
    }

    & $analyzer -RunRoot $fixtureRoot -ExpectedSharedShellShiftDp 50 -ToleranceDp 0.01

    $analysisPath = Join-Path $fixtureRoot 'ui-characterization-analysis.json'
    if (-not (Test-Path -LiteralPath $analysisPath -PathType Leaf)) {
        throw 'Analyzer did not produce ui-characterization-analysis.json.'
    }
    $analysis = Get-Content -LiteralPath $analysisPath -Raw | ConvertFrom-Json

    if ([int]$analysis.caseCount -ne 9) { throw "Expected 9 fixture cases, got $($analysis.caseCount)." }
    if ([int]$analysis.failedCaseCount -ne 0) { throw 'Synthetic fixture unexpectedly contains failed cases.' }
    if ([int]$analysis.comparisonCount -ne 3) { throw "Expected 3 profile comparison rows, got $($analysis.comparisonCount)." }
    if ([int]$analysis.representativeComparisonCount -ne 2) { throw 'Expected exactly two representative TV comparison rows.' }
    if ([int]$analysis.representativeFocusContractRowCount -ne 2) { throw 'Expected two representative focus-contract rows.' }
    if (-not ([bool]$analysis.allRepresentativeRowsMatchExpectedShift)) { throw 'Analyzer failed to identify the +50dp A→B fixture shift.' }
    if (-not ([bool]$analysis.allRepresentativeCandidateRowsMatchB)) { throw 'Analyzer failed to identify C=B in the fixture.' }
    if (-not ([bool]$analysis.allRepresentativeOriginsStableDuringRail)) { throw 'Analyzer failed the stable-during-rail fixture invariant.' }
    if (-not ([bool]$analysis.allRepresentativeOriginsRestoredAfterBack)) { throw 'Analyzer failed the restored-after-Back fixture invariant.' }
    if (-not ([bool]$analysis.allRepresentativeOriginsRestoredAfterRight)) { throw 'Analyzer failed the restored-after-Right fixture invariant.' }
    if (-not ([bool]$analysis.allEligibleFocusRowsReachRailForBack)) { throw 'Analyzer failed the Back rail-entry fixture invariant.' }
    if (-not ([bool]$analysis.allEligibleFocusRowsMoveAwayFromRailOnBack)) { throw 'Analyzer failed the Back focus-restoration fixture invariant.' }
    if (-not ([bool]$analysis.allEligibleFocusRowsReachRailForRight)) { throw 'Analyzer failed the Right rail-entry fixture invariant.' }
    if (-not ([bool]$analysis.allEligibleFocusRowsMoveAwayFromRailOnRight)) { throw 'Analyzer failed the Right focus-restoration fixture invariant.' }
    if (-not ([bool]$analysis.allRepresentativeRoutesRetainedAfterBack)) { throw 'Representative fixture routes should remain selected after Back.' }
    if (-not ([bool]$analysis.allRepresentativeRoutesRetainedAfterRight)) { throw 'Representative fixture routes should remain selected after Right.' }

    $representative = @($analysis.comparisons | Where-Object { $_.representativeTvMode })
    foreach ($row in $representative) {
        if ([math]::Abs([double]$row.deltaABDp - 50.0) -gt 0.01) {
            throw "Unexpected A→B delta for $($row.displayProfile): $($row.deltaABDp)dp"
        }
        if ([math]::Abs([double]$row.deltaBCDp) -gt 0.01) {
            throw "Unexpected B→C delta for $($row.displayProfile): $($row.deltaBCDp)dp"
        }
    }

    $compactRows = @($analysis.comparisons | Where-Object { $_.displayProfile -eq 'compact-stress' })
    if ($compactRows.Count -ne 1) { throw "Expected one compact-stress comparison row, got $($compactRows.Count)." }
    $compact = $compactRows[0]
    foreach ($property in @(
        'aRouteSelectedAfterBack', 'bRouteSelectedAfterBack', 'cRouteSelectedAfterBack',
        'aRouteSelectedAfterRight', 'bRouteSelectedAfterRight', 'cRouteSelectedAfterRight',
        'aAnchorPresentAfterBack', 'bAnchorPresentAfterBack', 'cAnchorPresentAfterBack',
        'aAnchorPresentAfterRight', 'bAnchorPresentAfterRight', 'cAnchorPresentAfterRight'
    )) {
        if ([bool]$compact.$property) {
            throw "Analyzer lost synthetic route-loss evidence: $property should be false."
        }
    }

    Write-Host 'TV UI characterization analyzer synthetic fixture passed.'
} finally {
    Remove-Item -LiteralPath $fixtureRoot -Recurse -Force -ErrorAction SilentlyContinue
}
