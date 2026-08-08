[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$LASTEXITCODE = 0
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourceSeriesScript = Join-Path $PSScriptRoot "Invoke-M3uCorpusSeries.ps1"
$sourceCommitGuard = Join-Path $repositoryRoot "tools\ci\Assert-EvidenceCommit.ps1"
$sourceWorktreeGuard = Join-Path $repositoryRoot "tools\ci\Assert-EvidenceWorktree.ps1"
$focusedWorkflow = Join-Path $repositoryRoot ".github\workflows\focused-m3u-evidence.yml"

foreach ($requiredPath in @(
    $sourceSeriesScript,
    $sourceCommitGuard,
    $sourceWorktreeGuard,
    $focusedWorkflow
)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Claim-eligible worktree provenance fixture dependency is missing: $requiredPath"
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("muxtv-m3u-claim-worktree-" + [Guid]::NewGuid().ToString("N"))
$fixtureRepository = Join-Path $tempRoot "repository with spaces"
$fixtureMeasurements = Join-Path $fixtureRepository "tools\measurements"
$fixtureCi = Join-Path $fixtureRepository "tools\ci"
$fakeGradle = Join-Path $fixtureRepository "gradlew.bat"
$gradleSentinel = Join-Path $fixtureRepository "gradle-invocations.txt"
$trackedFixture = Join-Path $fixtureRepository "tracked.txt"

function Invoke-GitFixture {
    param(
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $output = @(& git -C $fixtureRepository @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "Git fixture command failed with exit code ${exitCode}: git -C <fixture> $($Arguments -join ' ')`n$($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Invoke-FixtureSeries {
    param(
        [Parameter(Mandatory)][string]$SourceCommit,
        [Parameter(Mandatory)][int]$Repetitions,
        [Parameter(Mandatory)][string]$EvidenceRoot
    )

    $failed = $false
    Push-Location $fixtureRepository
    try {
        & (Join-Path $fixtureMeasurements "Invoke-M3uCorpusSeries.ps1") `
            -SourceCommit $SourceCommit `
            -SourceBranch "claim-worktree-contract" `
            -M3uProfile "small-1k" `
            -Repetitions $Repetitions `
            -EvidenceRoot $EvidenceRoot `
            -NoDaemon
    } catch {
        $failed = $true
    } finally {
        Pop-Location
        # Expected negative cases may execute a failing fake native Gradle command.
        # The contract converts that outcome into assertions below.
        $global:LASTEXITCODE = 0
    }
    return $failed
}

function Assert-EvidenceRootEmpty {
    param([Parameter(Mandatory)][string]$EvidenceRoot)

    $children = @(Get-ChildItem -LiteralPath $EvidenceRoot -Force -ErrorAction Stop)
    if ($children.Count -ne 0) {
        throw "Claim-eligible dirty tracked checkout created evidence before provenance rejection."
    }
}

try {
    New-Item -ItemType Directory -Force -Path $fixtureMeasurements, $fixtureCi | Out-Null
    Copy-Item -LiteralPath $sourceSeriesScript -Destination (Join-Path $fixtureMeasurements "Invoke-M3uCorpusSeries.ps1")
    Copy-Item -LiteralPath $sourceCommitGuard -Destination (Join-Path $fixtureCi "Assert-EvidenceCommit.ps1")
    Copy-Item -LiteralPath $sourceWorktreeGuard -Destination (Join-Path $fixtureCi "Assert-EvidenceWorktree.ps1")

    @'
@echo off
echo invoked>> "%~dp0gradle-invocations.txt"
exit /b 17
'@ | Set-Content -LiteralPath $fakeGradle -Encoding ascii
    Set-Content -LiteralPath $trackedFixture -Value "baseline" -Encoding utf8

    $null = Invoke-GitFixture -Arguments @("init", "--quiet")
    $null = Invoke-GitFixture -Arguments @("config", "user.email", "muxtv-ci@example.invalid")
    $null = Invoke-GitFixture -Arguments @("config", "user.name", "MuxTV CI")
    $null = Invoke-GitFixture -Arguments @("add", ".")
    $null = Invoke-GitFixture -Arguments @("commit", "--quiet", "-m", "fixture")
    $sourceCommit = [string](@(Invoke-GitFixture -Arguments @("rev-parse", "HEAD"))[0])
    if ($sourceCommit -notmatch '^[0-9a-f]{40}$') {
        throw "Fixture source commit is invalid."
    }

    # Claim-eligible series: dirty tracked source must fail before evidence or Gradle execution.
    Set-Content -LiteralPath $trackedFixture -Value "dirty claim source" -Encoding utf8
    $claimEvidenceRoot = Join-Path $fixtureRepository "claim-evidence"
    New-Item -ItemType Directory -Force -Path $claimEvidenceRoot | Out-Null
    Remove-Item -LiteralPath $gradleSentinel -Force -ErrorAction SilentlyContinue
    $claimFailed = Invoke-FixtureSeries `
        -SourceCommit $sourceCommit `
        -Repetitions 5 `
        -EvidenceRoot $claimEvidenceRoot
    if (-not $claimFailed) {
        throw "Claim-eligible dirty tracked checkout was accepted."
    }
    if (Test-Path -LiteralPath $gradleSentinel) {
        throw "Claim-eligible dirty tracked checkout reached Gradle before provenance rejection."
    }
    Assert-EvidenceRootEmpty -EvidenceRoot $claimEvidenceRoot

    # Exploratory series: the same tracked drift remains usable and reaches the fake Gradle boundary.
    $exploratoryEvidenceRoot = Join-Path $fixtureRepository "exploratory-evidence"
    New-Item -ItemType Directory -Force -Path $exploratoryEvidenceRoot | Out-Null
    Remove-Item -LiteralPath $gradleSentinel -Force -ErrorAction SilentlyContinue
    $exploratoryFailed = Invoke-FixtureSeries `
        -SourceCommit $sourceCommit `
        -Repetitions 2 `
        -EvidenceRoot $exploratoryEvidenceRoot
    if (-not $exploratoryFailed) {
        throw "Exploratory fixture unexpectedly completed despite the failing fake Gradle boundary."
    }
    if (-not (Test-Path -LiteralPath $gradleSentinel -PathType Leaf)) {
        throw "Exploratory dirty tracked checkout was incorrectly blocked before Gradle."
    }
    if (@(Get-ChildItem -LiteralPath $exploratoryEvidenceRoot -Force).Count -eq 0) {
        throw "Exploratory dirty tracked checkout did not create its non-claim evidence directory."
    }

    # Claim-eligible series with clean tracked state and unrelated untracked evidence must reach Gradle.
    $null = Invoke-GitFixture -Arguments @("checkout", "--", "tracked.txt")
    $untrackedEvidence = Join-Path $fixtureRepository ".work\evidence\existing\result.json"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $untrackedEvidence) | Out-Null
    Set-Content -LiteralPath $untrackedEvidence -Value "{}" -Encoding utf8
    $cleanClaimEvidenceRoot = Join-Path $fixtureRepository "clean-claim-evidence"
    New-Item -ItemType Directory -Force -Path $cleanClaimEvidenceRoot | Out-Null
    Remove-Item -LiteralPath $gradleSentinel -Force -ErrorAction SilentlyContinue
    $cleanClaimFailed = Invoke-FixtureSeries `
        -SourceCommit $sourceCommit `
        -Repetitions 5 `
        -EvidenceRoot $cleanClaimEvidenceRoot
    if (-not $cleanClaimFailed) {
        throw "Clean claim fixture unexpectedly completed despite the failing fake Gradle boundary."
    }
    if (-not (Test-Path -LiteralPath $gradleSentinel -PathType Leaf)) {
        throw "Claim-eligible clean tracked checkout was blocked by unrelated untracked evidence."
    }

    # Static integration/order guard: claim worktree provenance must be explicit and precede evidence creation.
    $seriesContent = Get-Content -LiteralPath $sourceSeriesScript -Raw -Encoding utf8
    foreach ($token in @(
        "Assert-EvidenceWorktree.ps1",
        '-RepositoryRoot $repositoryRoot',
        '$Repetitions -ge 5'
    )) {
        if ($seriesContent.IndexOf($token, [System.StringComparison]::Ordinal) -lt 0) {
            throw "Focused M3U series is missing claim worktree provenance token: $token"
        }
    }
    $worktreeIndex = $seriesContent.IndexOf("Assert-EvidenceWorktree.ps1", [System.StringComparison]::Ordinal)
    $evidenceCreationIndex = $seriesContent.IndexOf('New-Item -ItemType Directory -Path $seriesDirectory', [System.StringComparison]::Ordinal)
    if ($worktreeIndex -lt 0 -or $evidenceCreationIndex -lt 0 -or $worktreeIndex -gt $evidenceCreationIndex) {
        throw "Claim worktree provenance must execute before the focused series evidence directory is created."
    }

    $workflowContent = Get-Content -LiteralPath $focusedWorkflow -Raw -Encoding utf8
    if (
        $workflowContent.IndexOf("tools/ci/Assert-EvidenceWorktree.ps1", [System.StringComparison]::Ordinal) -lt 0 -and
        $workflowContent.IndexOf("tools/ci/**", [System.StringComparison]::Ordinal) -lt 0
    ) {
        throw "Accepted-main focused workflow does not trigger on worktree provenance helper changes."
    }

    Write-Host "Claim-eligible M3U tracked-worktree provenance integration contract passed."
} finally {
    $global:LASTEXITCODE = 0
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
