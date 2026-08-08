[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$SourceCommit,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$')]
    [string]$SourceBranch = "local",

    [ValidateSet("small-1k", "medium-10k", "large-50k")]
    [string]$M3uProfile = "medium-10k",

    [long]$M3uSeed = 20260728,

    [ValidateRange(2, 20)]
    [int]$Repetitions = 5,

    [ValidateRange(0, 20)]
    [int]$Warmups = 2,

    [ValidateRange(5, 100)]
    [int]$Iterations = 5,

    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,63}$')]
    [string]$RunnerLabel = "local-windows-x64",

    [string]$EvidenceRoot = ".work/evidence",

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
if (-not (Test-Path $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper was not found."
}
$assertEvidenceCommit = Join-Path $repositoryRoot "tools\ci\Assert-EvidenceCommit.ps1"
if (-not (Test-Path $assertEvidenceCommit -PathType Leaf)) {
    throw "Evidence commit provenance assertion was not found."
}
& $assertEvidenceCommit -ExpectedCommit $SourceCommit

if ($Repetitions -ge 5) {
    $assertEvidenceWorktree = Join-Path $repositoryRoot "tools\ci\Assert-EvidenceWorktree.ps1"
    if (-not (Test-Path $assertEvidenceWorktree -PathType Leaf)) {
        throw "Evidence worktree provenance assertion was not found."
    }
    & $assertEvidenceWorktree -RepositoryRoot $repositoryRoot
}

$resolvedEvidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$shortCommit = $SourceCommit.Substring(0, 12)
$seriesDirectory = Join-Path $resolvedEvidenceRoot "$timestamp-$shortCommit-m3u-$M3uProfile-series"
$inputDirectory = Join-Path $seriesDirectory "input"
$outputDirectory = Join-Path $seriesDirectory "output"
$requestDirectory = Join-Path $seriesDirectory "requests"
if (Test-Path -LiteralPath $seriesDirectory) {
    throw "M3U series evidence directory already exists."
}
New-Item -ItemType Directory -Path $seriesDirectory | Out-Null
New-Item -ItemType Directory -Path $inputDirectory, $outputDirectory, $requestDirectory | Out-Null

$manifestPath = Join-Path $seriesDirectory "m3u-series-run-manifest.json"
$manifestStagePath = Join-Path $seriesDirectory ".m3u-series-run-manifest.stage"
$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $SourceBranch.Trim()
    commit = $SourceCommit
    profile = $M3uProfile
    seed = $M3uSeed
    repetitions = $Repetitions
    warmups = $Warmups
    iterations = $Iterations
    runnerLabel = $RunnerLabel
    claimEligible = $false
    thresholdApplied = $false
    status = "running"
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    corpus = $null
    runs = @()
    analysisOutput = $null
    failureType = $null
    failureLine = $null
}

function Write-M3uSeriesManifest {
    $serializedManifest = $manifest | ConvertTo-Json -Depth 10
    try {
        Set-Content `
            -LiteralPath $manifestStagePath `
            -Value $serializedManifest `
            -Encoding utf8
        Move-Item `
            -LiteralPath $manifestStagePath `
            -Destination $manifestPath `
            -Force
    } finally {
        Remove-Item -LiteralPath $manifestStagePath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-M3uSeriesGradle {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$LogPath,
        [Parameter(Mandatory)][string]$FailureMessage
    )

    $output = @(& $gradleWrapper @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output | Tee-Object -FilePath $LogPath | ForEach-Object { Write-Host $_ }
    "exit_code=$exitCode" | Add-Content -Path $LogPath -Encoding utf8
    if ($exitCode -ne 0) {
        throw $FailureMessage
    }
}

Write-M3uSeriesManifest
$canonicalSha256 = $null
$canonicalByteCount = $null
$canonicalExpectedParsed = $null
$canonicalExpectedSkipped = $null
$canonicalExpectedWarnings = $null
$analysisRuns = [System.Collections.Generic.List[object]]::new()

try {
    Set-Location $repositoryRoot

    for ($repetition = 1; $repetition -le $Repetitions; $repetition++) {
        $suffix = $repetition.ToString("00")
        $reportName = "m3u-$M3uProfile-$suffix.json"
        $reportPath = Join-Path $inputDirectory $reportName
        $arguments = @(
            ":core:testing:measureM3uParse",
            "--stacktrace",
            "--console=plain",
            "-PmeasurementProfile=$M3uProfile",
            "-PmeasurementSeed=$M3uSeed",
            "-PmeasurementSourceCommit=$SourceCommit",
            "-PmeasurementWarmups=$Warmups",
            "-PmeasurementIterations=$Iterations",
            "-PmeasurementRunnerLabel=$RunnerLabel",
            "-PmeasurementOutput=$reportPath"
        )
        if ($NoDaemon) {
            $arguments += "--no-daemon"
        }

        Invoke-M3uSeriesGradle `
            -Arguments $arguments `
            -LogPath (Join-Path $seriesDirectory "m3u-$M3uProfile-$suffix.log") `
            -FailureMessage "M3U measurement repetition failed."

        if (-not (Test-Path $reportPath -PathType Leaf)) {
            throw "M3U measurement report is missing."
        }
        $report = Get-Content -Path $reportPath -Raw -Encoding utf8 | ConvertFrom-Json
        if ([string]$report.profile -cne $M3uProfile -or
            [long]$report.seed -ne $M3uSeed -or
            [string]$report.sourceCommit -cne $SourceCommit -or
            [int]$report.measuredIterations -ne $Iterations -or
            [bool]$report.thresholdApplied -or
            [int]$report.failureCount -ne 0) {
            throw "M3U measurement report does not match the requested series contract."
        }

        $sha256 = [string]$report.corpus.sha256
        $byteCount = [long]$report.corpus.utf8ByteCount
        $expectedParsed = [int]$report.expected.parsedEntries
        $expectedSkipped = [int]$report.expected.skippedEntries
        $expectedWarnings = [int]$report.expected.warningCount
        if ($sha256 -notmatch '^[0-9a-f]{64}$' -or
            $byteCount -le 0 -or
            $expectedParsed -le 0 -or
            $expectedSkipped -lt 0 -or
            $expectedWarnings -lt 0) {
            throw "M3U measurement report contains invalid corpus identity."
        }

        if ($null -eq $canonicalSha256) {
            $canonicalSha256 = $sha256
            $canonicalByteCount = $byteCount
            $canonicalExpectedParsed = $expectedParsed
            $canonicalExpectedSkipped = $expectedSkipped
            $canonicalExpectedWarnings = $expectedWarnings
            $manifest.corpus = [ordered]@{
                sha256 = $canonicalSha256
                utf8ByteCount = $canonicalByteCount
                expectedParsedEntries = $canonicalExpectedParsed
                expectedSkippedEntries = $canonicalExpectedSkipped
                expectedWarningCount = $canonicalExpectedWarnings
            }
        } elseif ($sha256 -cne $canonicalSha256 -or
            $byteCount -ne $canonicalByteCount -or
            $expectedParsed -ne $canonicalExpectedParsed -or
            $expectedSkipped -ne $canonicalExpectedSkipped -or
            $expectedWarnings -ne $canonicalExpectedWarnings) {
            throw "M3U corpus identity drifted between repetitions."
        }

        $run = [ordered]@{
            repetitionId = "host-$suffix"
            reportName = $reportName
            corpusSha256 = $sha256
            corpusUtf8ByteCount = $byteCount
        }
        $manifest.runs += $run
        $analysisRuns.Add([ordered]@{
            repetitionId = $run.repetitionId
            reportName = $run.reportName
        })
        Write-M3uSeriesManifest
    }

    $analysisOutputName = "m3u-$M3uProfile-variance.json"
    $requestPath = Join-Path $requestDirectory "m3u-$M3uProfile-request.json"
    [ordered]@{
        schemaVersion = 1
        family = "m3u-parse"
        outputName = $analysisOutputName
        runs = @($analysisRuns)
        androidProfile = $null
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $requestPath -Encoding utf8

    $analysisArguments = @(
        ":core:testing:analyzeMeasurementSeries",
        "--stacktrace",
        "--console=plain",
        "-PmeasurementSeriesRequest=$requestPath",
        "-PmeasurementSeriesInputDirectory=$inputDirectory",
        "-PmeasurementSeriesOutputDirectory=$outputDirectory"
    )
    if ($NoDaemon) {
        $analysisArguments += "--no-daemon"
    }
    Invoke-M3uSeriesGradle `
        -Arguments $analysisArguments `
        -LogPath (Join-Path $seriesDirectory "m3u-$M3uProfile-analysis.log") `
        -FailureMessage "M3U measurement series analysis failed."

    $analysisOutput = Join-Path $outputDirectory $analysisOutputName
    if (-not (Test-Path $analysisOutput -PathType Leaf)) {
        throw "M3U measurement variance report is missing."
    }

    $manifest.analysisOutput = $analysisOutputName
    $manifest.claimEligible = ($Repetitions -ge 5)
    $manifest.status = "passed"
    Write-Host "M3U measurement series passed."
    Write-Host "profile=$M3uProfile"
    Write-Host "repetitions=$Repetitions"
    Write-Host "corpusSha256=$canonicalSha256"
    Write-Host "claimEligible=$($manifest.claimEligible)"
    Write-Host "thresholdApplied=false"
} catch {
    $manifest.status = "failed"
    $manifest.claimEligible = $false
    $manifest.failureType = $_.Exception.GetType().FullName
    $manifest.failureLine = [int]$_.InvocationInfo.ScriptLineNumber
    Write-Host "M3U measurement series failed. See evidence."
    throw
} finally {
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    Write-M3uSeriesManifest
    Write-Host "Evidence: $seriesDirectory"
}
