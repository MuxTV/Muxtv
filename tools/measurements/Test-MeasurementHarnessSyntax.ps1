[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$evidenceDirectory = Join-Path $repositoryRoot ".work\evidence\measurement-harness-syntax"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$diagnosticPath = Join-Path $evidenceDirectory "measurement-harness-syntax.log"
$profileScript = Join-Path $PSScriptRoot "MeasurementProfiles.ps1"
$seriesEntryScript = Join-Path $PSScriptRoot "Invoke-MeasurementSeries.ps1"
$seriesCoreScript = Join-Path $PSScriptRoot "Invoke-MeasurementSeriesCore.ps1"
$m3uSeriesScript = Join-Path $PSScriptRoot "Invoke-M3uCorpusSeries.ps1"
$finalizerScript = Join-Path $PSScriptRoot "Finalize-MeasurementSeriesEvidence.ps1"
$m3uFinalizerContractScript = Join-Path $PSScriptRoot "Test-M3uSeriesFinalizerContract.ps1"
$worktreeContractScript = Join-Path $repositoryRoot "tools\ci\Test-EvidenceWorktreeContract.ps1"
$m3uWorktreeIntegrationContractScript = Join-Path $PSScriptRoot "Test-M3uSeriesWorktreeProvenanceContract.ps1"
$files = @(
    $profileScript,
    $seriesEntryScript,
    $seriesCoreScript,
    $m3uSeriesScript,
    $finalizerScript,
    $m3uFinalizerContractScript,
    $worktreeContractScript,
    $m3uWorktreeIntegrationContractScript
)
$messages = [System.Collections.Generic.List[string]]::new()

foreach ($file in $files) {
    if (-not (Test-Path $file -PathType Leaf)) {
        $messages.Add("Missing measurement harness script: " + [System.IO.Path]::GetFileName($file))
        continue
    }

    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile(
        $file,
        [ref]$tokens,
        [ref]$parseErrors
    )
    foreach ($parseError in @($parseErrors)) {
        $location = "{0}:{1}:{2}" -f `
            [System.IO.Path]::GetFileName($file), `
            $parseError.Extent.StartLineNumber, `
            $parseError.Extent.StartColumnNumber
        $messages.Add($location + " " + $parseError.Message)
    }
}

if (Test-Path $profileScript -PathType Leaf) {
    . $profileScript
    $expected = [ordered]@{
        "current-normal" = @{ Api = 36; Ram = 2048; Cpu = 2 }
        "old-edge-normal" = @{ Api = 26; Ram = 1536; Cpu = 2 }
        "current-low-ram" = @{ Api = 36; Ram = 1024; Cpu = 2 }
    }
    $ids = @(Get-MuxTvMeasurementProfileIds)
    if ($ids.Count -ne $expected.Count -or
        [string]::Join("|", $ids) -cne [string]::Join("|", @($expected.Keys))) {
        $messages.Add("Measurement profile IDs are not in the canonical order.")
    }
    foreach ($id in @($expected.Keys)) {
        $profile = Get-MuxTvMeasurementProfile -Id $id
        $contract = $expected[$id]
        if ([string]$profile.Id -cne $id -or
            [int]$profile.RequestedApi -ne [int]$contract.Api -or
            [int]$profile.RamMb -ne [int]$contract.Ram -or
            [int]$profile.CpuCores -ne [int]$contract.Cpu) {
            $messages.Add("Measurement profile contract is invalid: $id")
        }
    }
    try {
        $null = Get-MuxTvMeasurementProfile -Id "unknown-profile"
        $messages.Add("Unknown measurement profile was accepted.")
    } catch {
        # Expected fail-closed behavior.
    }
}

if (Test-Path $seriesEntryScript -PathType Leaf) {
    $entryContent = Get-Content -Path $seriesEntryScript -Raw -Encoding utf8
    foreach ($token in @(
        "Wait-MeasurementStableAndroidBoot",
        "consecutiveReadyChecks",
        "Invoke-MeasurementSeriesCore.ps1",
        "Set-Alias"
    )) {
        if ($entryContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement series entry point is missing required contract token: $token")
        }
    }
}

if (Test-Path $seriesCoreScript -PathType Leaf) {
    $seriesContent = Get-Content -Path $seriesCoreScript -Raw -Encoding utf8
    $requiredTokens = @(
        "finally",
        "Stop-TvEmulator",
        "Get-MuxTvCanonicalAvdName",
        "Invoke-CatalogDatabaseMeasurement.ps1",
        "Invoke-PlayerProxyMeasurement.ps1",
        "Stop-MeasurementGradleDaemons",
        ":core:testing:analyzeMeasurementSeries"
    )
    foreach ($token in $requiredTokens) {
        if ($seriesContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement series core is missing required contract token: $token")
        }
    }
    if ($seriesContent -notmatch '"-EntryCount", "50000"' -or
        $seriesContent -match '"-EntryCount", "10000"') {
        $messages.Add("Measurement series must use the canonical 50k catalog database profile.")
    }
    $forbiddenTokens = @(
        "ForEach-Object -Parallel",
        "Start-Job",
        "Start-ThreadJob",
        "MuxTV_VARIANCE_",
        "Remove-MeasurementAvd",
        "AllowOldEdgeFallback"
    )
    foreach ($token in $forbiddenTokens) {
        if ($seriesContent -match [regex]::Escape($token)) {
            $messages.Add("Measurement series core contains forbidden execution/device ownership: $token")
        }
    }
    $catalogChildIndex = $seriesContent.IndexOf('-File", $catalogMeasurementScript')
    $daemonHandoffIndex = $seriesContent.IndexOf(
        'Stop-MeasurementGradleDaemons -GradleWrapper',
        [Math]::Max(0, $catalogChildIndex),
        [System.StringComparison]::Ordinal
    )
    $playerChildIndex = $seriesContent.IndexOf('-File", $playerMeasurementScript')
    if ($catalogChildIndex -lt 0 -or
        $daemonHandoffIndex -lt 0 -or
        $playerChildIndex -lt 0 -or
        $daemonHandoffIndex -lt $catalogChildIndex -or
        $daemonHandoffIndex -gt $playerChildIndex) {
        $messages.Add("Measurement series must complete the Gradle daemon handoff between catalog and player child builds.")
    }
}

if (Test-Path $m3uSeriesScript -PathType Leaf) {
    $m3uSeriesContent = Get-Content -Path $m3uSeriesScript -Raw -Encoding utf8
    $requiredTokens = @(
        'ValidateSet("small-1k", "medium-10k", "large-50k")',
        '[int]$Repetitions = 5',
        ':core:testing:measureM3uParse',
        ':core:testing:analyzeMeasurementSeries',
        'corpusSha256',
        'claimEligible',
        'M3U corpus identity drifted between repetitions.',
        'M3U series evidence directory already exists.',
        'Assert-EvidenceCommit.ps1',
        '-ExpectedCommit $SourceCommit'
    )
    foreach ($token in $requiredTokens) {
        if ($m3uSeriesContent -notmatch [regex]::Escape($token)) {
            $messages.Add("M3U corpus series is missing required contract token: $token")
        }
    }

    $provenanceIndex = $m3uSeriesContent.IndexOf('Assert-EvidenceCommit.ps1', [System.StringComparison]::Ordinal)
    $evidenceCreationIndex = $m3uSeriesContent.IndexOf('New-Item -ItemType Directory -Path $seriesDirectory', [System.StringComparison]::Ordinal)
    if ($provenanceIndex -lt 0 -or
        $evidenceCreationIndex -lt 0 -or
        $provenanceIndex -gt $evidenceCreationIndex) {
        $messages.Add("M3U corpus series must verify exact source-head provenance before creating the series evidence directory.")
    }

    foreach ($token in @("ForEach-Object -Parallel", "Start-Job", "Start-ThreadJob")) {
        if ($m3uSeriesContent -match [regex]::Escape($token)) {
            $messages.Add("M3U corpus series contains forbidden parallel execution: $token")
        }
    }

    $negativeEvidenceRoot = Join-Path $evidenceDirectory "wrong-source-commit"
    Remove-Item -LiteralPath $negativeEvidenceRoot -Recurse -Force -ErrorAction SilentlyContinue
    $wrongSourceCommit = "0" * 40
    $negativeFailure = $null
    try {
        & $m3uSeriesScript `
            -SourceCommit $wrongSourceCommit `
            -SourceBranch "measurement-harness-negative" `
            -M3uProfile "small-1k" `
            -Repetitions 2 `
            -EvidenceRoot $negativeEvidenceRoot `
            -NoDaemon
        $messages.Add("M3U corpus series accepted a SourceCommit that does not match the checked-out Git HEAD.")
    } catch {
        $negativeFailure = $_.Exception.Message
    }
    if ($null -ne $negativeFailure -and
        $negativeFailure -notlike "Evidence commit provenance mismatch:*") {
        $messages.Add("M3U corpus series wrong-commit smoke failed for an unexpected reason: $negativeFailure")
    }
    if (Test-Path -LiteralPath $negativeEvidenceRoot) {
        $messages.Add("M3U corpus series created evidence before rejecting a mismatched SourceCommit.")
    }
}

if (Test-Path $finalizerScript -PathType Leaf) {
    $finalizerContent = Get-Content -Path $finalizerScript -Raw -Encoding utf8
    foreach ($token in @("measurement-series-interrupted", 'status = "interrupted"')) {
        if ($finalizerContent -notmatch [regex]::Escape($token)) {
            $messages.Add("Measurement finalizer is missing required contract token: $token")
        }
    }
}

if ($messages.Count -eq 0 -and (Test-Path $m3uFinalizerContractScript -PathType Leaf)) {
    try {
        & $m3uFinalizerContractScript
    } catch {
        $messages.Add("Focused M3U finalizer contract failed: $($_.Exception.Message)")
    }
}

if ($messages.Count -eq 0 -and (Test-Path $worktreeContractScript -PathType Leaf)) {
    try {
        & $worktreeContractScript
        # The contract intentionally executes failing native Git commands for negative cases.
        # Any real contract failure is promoted to an exception, so a successful return owns
        # resetting native-command status before this harness returns to its caller.
        $global:LASTEXITCODE = 0
    } catch {
        $messages.Add("Evidence worktree provenance contract failed: $($_.Exception.Message)")
    }
}

if ($messages.Count -eq 0 -and (Test-Path $m3uWorktreeIntegrationContractScript -PathType Leaf)) {
    try {
        & $m3uWorktreeIntegrationContractScript
        $global:LASTEXITCODE = 0
    } catch {
        $messages.Add("Claim-eligible M3U worktree provenance integration contract failed: $($_.Exception.Message)")
    }
}

if ($messages.Count -gt 0) {
    $message = "Measurement harness validation failed." + [Environment]::NewLine +
        [string]::Join([Environment]::NewLine, $messages)
    Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
    Write-Host $message
    throw "Measurement harness validation failed."
}

$message = "Measurement profile catalog and sequential harness syntax are valid."
Set-Content -Path $diagnosticPath -Value $message -Encoding utf8
Write-Host $message
