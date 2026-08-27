[CmdletBinding()]
param(
    [string]$RunRoot,
    [double]$ExpectedSharedShellShiftDp = 50.0,
    [double]$ToleranceDp = 2.0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidenceRoot = Join-Path $repositoryRoot '.work\evidence\ui-characterization'

if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    if (-not (Test-Path -LiteralPath $evidenceRoot -PathType Container)) {
        throw "UI characterization evidence root does not exist: $evidenceRoot"
    }
    $latest = Get-ChildItem -LiteralPath $evidenceRoot -Directory |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($null -eq $latest) {
        throw "No UI characterization run directory exists below $evidenceRoot"
    }
    $RunRoot = $latest.FullName
} elseif (-not [System.IO.Path]::IsPathRooted($RunRoot)) {
    $RunRoot = Join-Path $repositoryRoot $RunRoot
}

$RunRoot = (Resolve-Path -LiteralPath $RunRoot).Path
$expectedComparisons = @('A', 'B', 'C')
$manifestFiles = @(Get-ChildItem -LiteralPath $RunRoot -Filter 'case-manifest.json' -File -Recurse)
if ($manifestFiles.Count -eq 0) {
    throw "No case-manifest.json files found below $RunRoot"
}

function Get-RelativeSegments {
    param([Parameter(Mandatory)][string]$Path)
    $relative = [System.IO.Path]::GetRelativePath($RunRoot, $Path)
    return @($relative -split '[\\/]')
}

function Convert-PxToDp {
    param(
        [Parameter(Mandatory)][double]$Pixels,
        [Parameter(Mandatory)][double]$DensityDpi
    )
    return $Pixels * 160.0 / $DensityDpi
}

function Get-NullableBoundsLeft {
    param($Bounds)
    if ($null -eq $Bounds) { return $null }
    return [double]$Bounds.left
}

function Get-OptionalBoolean {
    param(
        [Parameter(Mandatory)][object]$Object,
        [Parameter(Mandatory)][string]$Name
    )
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $null }
    return [bool]$property.Value
}

$caseRecords = [System.Collections.Generic.List[object]]::new()
$destinationRecords = [System.Collections.Generic.List[object]]::new()

foreach ($manifestFile in $manifestFiles) {
    $segments = Get-RelativeSegments -Path $manifestFile.FullName
    if ($segments.Count -lt 3) {
        throw "Unexpected case manifest location: $($manifestFile.FullName)"
    }

    $comparisonId = $segments[0]
    if ($comparisonId -notin $expectedComparisons) {
        throw "Unexpected comparison id '$comparisonId' in $($manifestFile.FullName)"
    }

    $manifest = Get-Content -LiteralPath $manifestFile.FullName -Raw | ConvertFrom-Json
    $caseDirectory = $manifestFile.Directory.FullName
    $probeFiles = @(Get-ChildItem -LiteralPath $caseDirectory -Filter 'probe-result.json' -File -Recurse)
    $probePath = if ($probeFiles.Count -eq 1) { $probeFiles[0].FullName } else { $null }

    $caseRecord = [pscustomobject]@{
        comparisonId = $comparisonId
        sourceCommit = [string]$manifest.sourceCommit
        displayProfile = [string]$manifest.displayProfile
        displayWidthPx = [int]$manifest.displayWidthPx
        displayHeightPx = [int]$manifest.displayHeightPx
        displayDensityDpi = [int]$manifest.displayDensityDpi
        representativeTvMode = [bool]$manifest.representativeTvMode
        probeSha256 = [string]$manifest.probeSha256
        avdName = [string]$manifest.avdName
        status = [string]$manifest.status
        failure = $manifest.failure
        probeResultPath = $probePath
    }
    $caseRecords.Add($caseRecord)

    if ($caseRecord.status -ne 'passed') { continue }
    if ($null -eq $probePath) {
        throw "Passed case $comparisonId/$($caseRecord.displayProfile) does not contain exactly one probe-result.json"
    }

    $probe = Get-Content -LiteralPath $probePath -Raw | ConvertFrom-Json
    if ([string]$probe.sourceCommit -cne $caseRecord.sourceCommit) {
        throw "Probe source commit mismatch for $comparisonId/$($caseRecord.displayProfile)"
    }
    if ([string]$probe.displayProfile -cne $caseRecord.displayProfile) {
        throw "Probe display profile mismatch for $comparisonId/$($caseRecord.displayProfile)"
    }

    foreach ($destination in @($probe.destinations)) {
        $beforeLeft = [double]$destination.beforeBounds.left
        $duringLeft = [double]$destination.duringRailBounds.left
        $afterRightLeft = Get-NullableBoundsLeft -Bounds $destination.afterRightBounds
        $afterBackLeft = Get-NullableBoundsLeft -Bounds $destination.afterBackBounds
        $railItemWidthPx = [double]$destination.railBounds.width
        $densityDpi = [double]$caseRecord.displayDensityDpi

        $destinationRecords.Add([pscustomobject]@{
            comparisonId = $comparisonId
            sourceCommit = $caseRecord.sourceCommit
            displayProfile = $caseRecord.displayProfile
            representativeTvMode = $caseRecord.representativeTvMode
            displayDensityDpi = $caseRecord.displayDensityDpi
            destination = [string]$destination.destination
            anchor = [string]$destination.anchor
            anchorHasExplicitFocusAction = [bool]$destination.anchorHasExplicitFocusAction
            beforeLeftPx = $beforeLeft
            duringRailLeftPx = $duringLeft
            afterBackLeftPx = $afterBackLeft
            afterRightLeftPx = $afterRightLeft
            beforeLeftDp = [math]::Round((Convert-PxToDp -Pixels $beforeLeft -DensityDpi $densityDpi), 3)
            railItemWidthPx = $railItemWidthPx
            railItemWidthDp = [math]::Round((Convert-PxToDp -Pixels $railItemWidthPx -DensityDpi $densityDpi), 3)
            routeSelectedAfterBack = Get-OptionalBoolean -Object $destination -Name 'routeSelectedAfterBack'
            anchorPresentAfterBack = Get-OptionalBoolean -Object $destination -Name 'anchorPresentAfterBack'
            routeSelectedAfterRight = Get-OptionalBoolean -Object $destination -Name 'routeSelectedAfterRight'
            anchorPresentAfterRight = Get-OptionalBoolean -Object $destination -Name 'anchorPresentAfterRight'
            contentOriginStableDuringRail = [bool]$destination.contentOriginStableDuringRail
            contentOriginStableDuringBackRail = [bool]$destination.contentOriginStableDuringBackRail
            contentOriginRestoredAfterBack = [bool]$destination.contentOriginRestoredAfterBack
            contentOriginRestoredAfterRight = [bool]$destination.contentOriginRestoredAfterRight
            backReachedExpectedRailItem = [bool]$destination.backReachedExpectedRailItem
            backMovedFocusAwayFromRail = [bool]$destination.backMovedFocusAwayFromRail
            rightReachedExpectedRailItem = [bool]$destination.rightReachedExpectedRailItem
            rightMovedFocusAwayFromRail = [bool]$destination.rightMovedFocusAwayFromRail
            focusInitial = $destination.focusInitial
            focusBeforeLeft = $destination.focusBeforeLeft
            focusBeforeBack = $destination.focusBeforeBack
            focusAfterBack = $destination.focusAfterBack
            focusBeforeSecondLeft = $destination.focusBeforeSecondLeft
            focusOnRailBeforeRight = $destination.focusOnRailBeforeRight
            focusAfterRight = $destination.focusAfterRight
        })
    }
}

$failedCases = @($caseRecords | Where-Object { $_.status -ne 'passed' })
$probeHashes = @($caseRecords | ForEach-Object probeSha256 | Sort-Object -Unique)
$avdNames = @($caseRecords | ForEach-Object avdName | Sort-Object -Unique)
if ($probeHashes.Count -ne 1) {
    throw "Characterization cases do not share one probe SHA256: $($probeHashes -join ', ')"
}
if ($avdNames.Count -ne 1 -or $avdNames[0] -cne 'MuxTV_TV_CURRENT_API36') {
    throw "Characterization evidence used unexpected AVD identities: $($avdNames -join ', ')"
}

$comparisons = [System.Collections.Generic.List[object]]::new()
$groups = $destinationRecords | Group-Object displayProfile, destination
foreach ($group in $groups) {
    $rows = @($group.Group)
    $byId = @{}
    foreach ($row in $rows) { $byId[$row.comparisonId] = $row }
    $missing = @($expectedComparisons | Where-Object { -not $byId.ContainsKey($_) })
    if ($missing.Count -gt 0) { continue }

    $a = $byId['A']
    $b = $byId['B']
    $c = $byId['C']
    $density = [double]$a.displayDensityDpi
    if ($density -ne [double]$b.displayDensityDpi -or $density -ne [double]$c.displayDensityDpi) {
        throw "Density mismatch inside comparison group $($group.Name)"
    }

    $deltaAbDp = Convert-PxToDp -Pixels ([double]$b.beforeLeftPx - [double]$a.beforeLeftPx) -DensityDpi $density
    $deltaBcDp = Convert-PxToDp -Pixels ([double]$c.beforeLeftPx - [double]$b.beforeLeftPx) -DensityDpi $density
    $deltaAcDp = Convert-PxToDp -Pixels ([double]$c.beforeLeftPx - [double]$a.beforeLeftPx) -DensityDpi $density
    $focusContractEligible = $a.anchorHasExplicitFocusAction -and $b.anchorHasExplicitFocusAction -and $c.anchorHasExplicitFocusAction

    $comparisons.Add([pscustomobject]@{
        displayProfile = $a.displayProfile
        representativeTvMode = $a.representativeTvMode
        destination = $a.destination
        densityDpi = [int]$density
        focusContractEligible = $focusContractEligible
        aContentOriginDp = $a.beforeLeftDp
        bContentOriginDp = $b.beforeLeftDp
        cContentOriginDp = $c.beforeLeftDp
        deltaABDp = [math]::Round($deltaAbDp, 3)
        deltaBCDp = [math]::Round($deltaBcDp, 3)
        deltaACDp = [math]::Round($deltaAcDp, 3)
        abMatchesExpectedSharedShellShift = [math]::Abs($deltaAbDp - $ExpectedSharedShellShiftDp) -le $ToleranceDp
        cMatchesBContentOrigin = [math]::Abs($deltaBcDp) -le $ToleranceDp
        aRailItemWidthDp = $a.railItemWidthDp
        bRailItemWidthDp = $b.railItemWidthDp
        cRailItemWidthDp = $c.railItemWidthDp
        aStableDuringRail = $a.contentOriginStableDuringRail
        bStableDuringRail = $b.contentOriginStableDuringRail
        cStableDuringRail = $c.contentOriginStableDuringRail
        aRestoredAfterBack = $a.contentOriginRestoredAfterBack
        bRestoredAfterBack = $b.contentOriginRestoredAfterBack
        cRestoredAfterBack = $c.contentOriginRestoredAfterBack
        aRestoredAfterRight = $a.contentOriginRestoredAfterRight
        bRestoredAfterRight = $b.contentOriginRestoredAfterRight
        cRestoredAfterRight = $c.contentOriginRestoredAfterRight
        aRouteSelectedAfterBack = $a.routeSelectedAfterBack
        bRouteSelectedAfterBack = $b.routeSelectedAfterBack
        cRouteSelectedAfterBack = $c.routeSelectedAfterBack
        aAnchorPresentAfterBack = $a.anchorPresentAfterBack
        bAnchorPresentAfterBack = $b.anchorPresentAfterBack
        cAnchorPresentAfterBack = $c.anchorPresentAfterBack
        aRouteSelectedAfterRight = $a.routeSelectedAfterRight
        bRouteSelectedAfterRight = $b.routeSelectedAfterRight
        cRouteSelectedAfterRight = $c.routeSelectedAfterRight
        aAnchorPresentAfterRight = $a.anchorPresentAfterRight
        bAnchorPresentAfterRight = $b.anchorPresentAfterRight
        cAnchorPresentAfterRight = $c.anchorPresentAfterRight
        aBackReachedRail = $a.backReachedExpectedRailItem
        bBackReachedRail = $b.backReachedExpectedRailItem
        cBackReachedRail = $c.backReachedExpectedRailItem
        aBackMovedAway = $a.backMovedFocusAwayFromRail
        bBackMovedAway = $b.backMovedFocusAwayFromRail
        cBackMovedAway = $c.backMovedFocusAwayFromRail
        aRightReachedRail = $a.rightReachedExpectedRailItem
        bRightReachedRail = $b.rightReachedExpectedRailItem
        cRightReachedRail = $c.rightReachedExpectedRailItem
        aRightMovedAway = $a.rightMovedFocusAwayFromRail
        bRightMovedAway = $b.rightMovedFocusAwayFromRail
        cRightMovedAway = $c.rightMovedFocusAwayFromRail
        aFocusAfterBack = $a.focusAfterBack
        bFocusAfterBack = $b.focusAfterBack
        cFocusAfterBack = $c.focusAfterBack
        aFocusAfterRight = $a.focusAfterRight
        bFocusAfterRight = $b.focusAfterRight
        cFocusAfterRight = $c.focusAfterRight
    })
}

$representative = @($comparisons | Where-Object { $_.representativeTvMode })
$expectedShiftRows = @($representative | Where-Object { $_.abMatchesExpectedSharedShellShift })
$cMatchesBRows = @($representative | Where-Object { $_.cMatchesBContentOrigin })
$stableRows = @($representative | Where-Object { $_.aStableDuringRail -and $_.bStableDuringRail -and $_.cStableDuringRail })
$backRestoredRows = @($representative | Where-Object { $_.aRestoredAfterBack -and $_.bRestoredAfterBack -and $_.cRestoredAfterBack })
$rightRestoredRows = @($representative | Where-Object { $_.aRestoredAfterRight -and $_.bRestoredAfterRight -and $_.cRestoredAfterRight })
$backRouteRetainedRows = @($representative | Where-Object { $_.aRouteSelectedAfterBack -eq $true -and $_.bRouteSelectedAfterBack -eq $true -and $_.cRouteSelectedAfterBack -eq $true })
$rightRouteRetainedRows = @($representative | Where-Object { $_.aRouteSelectedAfterRight -eq $true -and $_.bRouteSelectedAfterRight -eq $true -and $_.cRouteSelectedAfterRight -eq $true })
$backAnchorPresentRows = @($representative | Where-Object { $_.aAnchorPresentAfterBack -eq $true -and $_.bAnchorPresentAfterBack -eq $true -and $_.cAnchorPresentAfterBack -eq $true })
$rightAnchorPresentRows = @($representative | Where-Object { $_.aAnchorPresentAfterRight -eq $true -and $_.bAnchorPresentAfterRight -eq $true -and $_.cAnchorPresentAfterRight -eq $true })
$focusRows = @($representative | Where-Object { $_.focusContractEligible })
$backReachedRows = @($focusRows | Where-Object { $_.aBackReachedRail -and $_.bBackReachedRail -and $_.cBackReachedRail })
$backMovedRows = @($focusRows | Where-Object { $_.aBackMovedAway -and $_.bBackMovedAway -and $_.cBackMovedAway })
$rightReachedRows = @($focusRows | Where-Object { $_.aRightReachedRail -and $_.bRightReachedRail -and $_.cRightReachedRail })
$rightMovedRows = @($focusRows | Where-Object { $_.aRightMovedAway -and $_.bRightMovedAway -and $_.cRightMovedAway })

$sourceFactsPath = Join-Path (Split-Path -Parent $RunRoot) 'source-facts.json'
$sourceFacts = if (Test-Path -LiteralPath $sourceFactsPath -PathType Leaf) {
    Get-Content -LiteralPath $sourceFactsPath -Raw | ConvertFrom-Json
} else {
    $null
}

$analysis = [ordered]@{
    schemaVersion = 3
    runRoot = $RunRoot
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    probeSha256 = $probeHashes[0]
    avdName = $avdNames[0]
    expectedSharedShellShiftDp = $ExpectedSharedShellShiftDp
    toleranceDp = $ToleranceDp
    sourceFactsPath = if ($null -eq $sourceFacts) { $null } else { $sourceFactsPath }
    sourceFacts = $sourceFacts
    caseCount = $caseRecords.Count
    failedCaseCount = $failedCases.Count
    destinationObservationCount = $destinationRecords.Count
    comparisonCount = $comparisons.Count
    representativeComparisonCount = $representative.Count
    representativeExpectedShiftMatchCount = $expectedShiftRows.Count
    representativeCandidateMatchesBCount = $cMatchesBRows.Count
    representativeStableDuringRailCount = $stableRows.Count
    representativeRestoredAfterBackCount = $backRestoredRows.Count
    representativeRestoredAfterRightCount = $rightRestoredRows.Count
    representativeRoutesRetainedAfterBackCount = $backRouteRetainedRows.Count
    representativeRoutesRetainedAfterRightCount = $rightRouteRetainedRows.Count
    representativeAnchorsPresentAfterBackCount = $backAnchorPresentRows.Count
    representativeAnchorsPresentAfterRightCount = $rightAnchorPresentRows.Count
    representativeFocusContractRowCount = $focusRows.Count
    representativeBackReachedRailCount = $backReachedRows.Count
    representativeBackMovedAwayCount = $backMovedRows.Count
    representativeRightReachedRailCount = $rightReachedRows.Count
    representativeRightMovedAwayCount = $rightMovedRows.Count
    allRepresentativeRowsMatchExpectedShift = $representative.Count -gt 0 -and $expectedShiftRows.Count -eq $representative.Count
    allRepresentativeCandidateRowsMatchB = $representative.Count -gt 0 -and $cMatchesBRows.Count -eq $representative.Count
    allRepresentativeOriginsStableDuringRail = $representative.Count -gt 0 -and $stableRows.Count -eq $representative.Count
    allRepresentativeOriginsRestoredAfterBack = $representative.Count -gt 0 -and $backRestoredRows.Count -eq $representative.Count
    allRepresentativeOriginsRestoredAfterRight = $representative.Count -gt 0 -and $rightRestoredRows.Count -eq $representative.Count
    allRepresentativeRoutesRetainedAfterBack = $representative.Count -gt 0 -and $backRouteRetainedRows.Count -eq $representative.Count
    allRepresentativeRoutesRetainedAfterRight = $representative.Count -gt 0 -and $rightRouteRetainedRows.Count -eq $representative.Count
    allRepresentativeAnchorsPresentAfterBack = $representative.Count -gt 0 -and $backAnchorPresentRows.Count -eq $representative.Count
    allRepresentativeAnchorsPresentAfterRight = $representative.Count -gt 0 -and $rightAnchorPresentRows.Count -eq $representative.Count
    allEligibleFocusRowsReachRailForBack = $focusRows.Count -gt 0 -and $backReachedRows.Count -eq $focusRows.Count
    allEligibleFocusRowsMoveAwayFromRailOnBack = $focusRows.Count -gt 0 -and $backMovedRows.Count -eq $focusRows.Count
    allEligibleFocusRowsReachRailForRight = $focusRows.Count -gt 0 -and $rightReachedRows.Count -eq $focusRows.Count
    allEligibleFocusRowsMoveAwayFromRailOnRight = $focusRows.Count -gt 0 -and $rightMovedRows.Count -eq $focusRows.Count
    cases = @($caseRecords)
    comparisons = @($comparisons)
}

$jsonPath = Join-Path $RunRoot 'ui-characterization-analysis.json'
$analysis | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$markdownPath = Join-Path $RunRoot 'ui-characterization-analysis.md'
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# MuxTV TV UI characterization analysis')
$lines.Add('')
$lines.Add(('- Probe SHA256: `{0}`' -f $probeHashes[0]))
$lines.Add(('- AVD: `{0}`' -f $avdNames[0]))
$lines.Add("- Cases: $($caseRecords.Count); failed: $($failedCases.Count)")
$lines.Add("- Representative comparison rows: $($representative.Count)")
$lines.Add("- A→B expected +$ExpectedSharedShellShiftDp dp matches: $($expectedShiftRows.Count)/$($representative.Count)")
$lines.Add("- C matches B content origin within ±$ToleranceDp dp: $($cMatchesBRows.Count)/$($representative.Count)")
$lines.Add("- Back retains route: $($backRouteRetainedRows.Count)/$($representative.Count); anchor remains: $($backAnchorPresentRows.Count)/$($representative.Count)")
$lines.Add("- Right retains route: $($rightRouteRetainedRows.Count)/$($representative.Count); anchor remains: $($rightAnchorPresentRows.Count)/$($representative.Count)")
$lines.Add("- Representative focus-contract rows: $($focusRows.Count)")
$lines.Add("- Back reaches rail: $($backReachedRows.Count)/$($focusRows.Count); Back moves away: $($backMovedRows.Count)/$($focusRows.Count)")
$lines.Add("- Right reaches rail: $($rightReachedRows.Count)/$($focusRows.Count); Right moves away: $($rightMovedRows.Count)/$($focusRows.Count)")
$lines.Add('')
$lines.Add('| Profile | Destination | Focus contract | A origin dp | B origin dp | C origin dp | A→B dp | B→C dp | A item width dp | B item width dp | C item width dp |')
$lines.Add('| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $comparisons | Sort-Object displayProfile, destination) {
    $lines.Add("| $($row.displayProfile) | $($row.destination) | $($row.focusContractEligible) | $($row.aContentOriginDp) | $($row.bContentOriginDp) | $($row.cContentOriginDp) | $($row.deltaABDp) | $($row.deltaBCDp) | $($row.aRailItemWidthDp) | $($row.bRailItemWidthDp) | $($row.cRailItemWidthDp) |")
}
$lines | Set-Content -LiteralPath $markdownPath -Encoding utf8

if ($failedCases.Count -gt 0) {
    $failures = $failedCases | ForEach-Object { "$($_.comparisonId)/$($_.displayProfile): $($_.failure)" }
    throw "UI characterization contains failed cases.`n$($failures -join [Environment]::NewLine)"
}

Write-Host "UI characterization analysis JSON: $jsonPath"
Write-Host "UI characterization analysis Markdown: $markdownPath"
