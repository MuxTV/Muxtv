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

    if ($caseRecord.status -ne 'passed') {
        continue
    }
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
        $afterLeft = [double]$destination.afterBounds.left
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
            beforeLeftPx = $beforeLeft
            duringRailLeftPx = $duringLeft
            afterLeftPx = $afterLeft
            beforeLeftDp = [math]::Round((Convert-PxToDp -Pixels $beforeLeft -DensityDpi $densityDpi), 3)
            railItemWidthPx = $railItemWidthPx
            railItemWidthDp = [math]::Round((Convert-PxToDp -Pixels $railItemWidthPx -DensityDpi $densityDpi), 3)
            contentOriginStableDuringRail = [bool]$destination.contentOriginStableDuringRail
            contentOriginRestored = [bool]$destination.contentOriginRestored
            focusInitial = $destination.focusInitial
            focusBeforeLeft = $destination.focusBeforeLeft
            focusOnRail = $destination.focusOnRail
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
    if ($missing.Count -gt 0) {
        continue
    }

    $a = $byId['A']
    $b = $byId['B']
    $c = $byId['C']
    $density = [double]$a.displayDensityDpi
    if ($density -ne [double]$b.displayDensityDpi -or $density -ne [double]$c.displayDensityDpi) {
        throw "Density mismatch inside comparison group $($group.Name)"
    }

    $deltaAbPx = [double]$b.beforeLeftPx - [double]$a.beforeLeftPx
    $deltaBcPx = [double]$c.beforeLeftPx - [double]$b.beforeLeftPx
    $deltaAcPx = [double]$c.beforeLeftPx - [double]$a.beforeLeftPx
    $deltaAbDp = Convert-PxToDp -Pixels $deltaAbPx -DensityDpi $density
    $deltaBcDp = Convert-PxToDp -Pixels $deltaBcPx -DensityDpi $density
    $deltaAcDp = Convert-PxToDp -Pixels $deltaAcPx -DensityDpi $density

    $comparisons.Add([pscustomobject]@{
        displayProfile = $a.displayProfile
        representativeTvMode = $a.representativeTvMode
        destination = $a.destination
        densityDpi = [int]$density
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
        aRestoredAfterRail = $a.contentOriginRestored
        bRestoredAfterRail = $b.contentOriginRestored
        cRestoredAfterRail = $c.contentOriginRestored
        aFocusOnRail = $a.focusOnRail
        bFocusOnRail = $b.focusOnRail
        cFocusOnRail = $c.focusOnRail
    })
}

$representative = @($comparisons | Where-Object { $_.representativeTvMode })
$expectedShiftRows = @($representative | Where-Object { $_.abMatchesExpectedSharedShellShift })
$cMatchesBRows = @($representative | Where-Object { $_.cMatchesBContentOrigin })
$stableRows = @($representative | Where-Object { $_.aStableDuringRail -and $_.bStableDuringRail -and $_.cStableDuringRail })
$restoredRows = @($representative | Where-Object { $_.aRestoredAfterRail -and $_.bRestoredAfterRail -and $_.cRestoredAfterRail })

$analysis = [ordered]@{
    schemaVersion = 1
    runRoot = $RunRoot
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    probeSha256 = $probeHashes[0]
    avdName = $avdNames[0]
    expectedSharedShellShiftDp = $ExpectedSharedShellShiftDp
    toleranceDp = $ToleranceDp
    caseCount = $caseRecords.Count
    failedCaseCount = $failedCases.Count
    destinationObservationCount = $destinationRecords.Count
    comparisonCount = $comparisons.Count
    representativeComparisonCount = $representative.Count
    representativeExpectedShiftMatchCount = $expectedShiftRows.Count
    representativeCandidateMatchesBCount = $cMatchesBRows.Count
    representativeStableDuringRailCount = $stableRows.Count
    representativeRestoredAfterRailCount = $restoredRows.Count
    allRepresentativeRowsMatchExpectedShift = $representative.Count -gt 0 -and $expectedShiftRows.Count -eq $representative.Count
    allRepresentativeCandidateRowsMatchB = $representative.Count -gt 0 -and $cMatchesBRows.Count -eq $representative.Count
    allRepresentativeOriginsStableDuringRail = $representative.Count -gt 0 -and $stableRows.Count -eq $representative.Count
    allRepresentativeOriginsRestored = $representative.Count -gt 0 -and $restoredRows.Count -eq $representative.Count
    cases = @($caseRecords)
    comparisons = @($comparisons)
}

$jsonPath = Join-Path $RunRoot 'ui-characterization-analysis.json'
$analysis | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $jsonPath -Encoding utf8

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
$lines.Add('')
$lines.Add('| Profile | Destination | A origin dp | B origin dp | C origin dp | A→B dp | B→C dp | A item width dp | B item width dp | C item width dp |')
$lines.Add('| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
foreach ($row in $comparisons | Sort-Object displayProfile, destination) {
    $lines.Add("| $($row.displayProfile) | $($row.destination) | $($row.aContentOriginDp) | $($row.bContentOriginDp) | $($row.cContentOriginDp) | $($row.deltaABDp) | $($row.deltaBCDp) | $($row.aRailItemWidthDp) | $($row.bRailItemWidthDp) | $($row.cRailItemWidthDp) |")
}
$lines | Set-Content -LiteralPath $markdownPath -Encoding utf8

if ($failedCases.Count -gt 0) {
    $failures = $failedCases | ForEach-Object { "$($_.comparisonId)/$($_.displayProfile): $($_.failure)" }
    throw "UI characterization contains failed cases.`n$($failures -join [Environment]::NewLine)"
}

Write-Host "UI characterization analysis JSON: $jsonPath"
Write-Host "UI characterization analysis Markdown: $markdownPath"
