[CmdletBinding()]
param(
    [ValidateSet("Fast", "Full", "Device")]
    [string]$Mode = "Fast",

    [string]$EvidenceRoot = ".work/evidence",

    [string]$SourceBranch = "",

    [string]$SourceCommit = "",

    [switch]$NoDaemon
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"

if (-not (Test-Path $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

Set-Location $repositoryRoot

function Get-GitValue {
    param([string[]]$Arguments, [string]$Fallback)

    try {
        $firstLine = & git @Arguments 2>$null | Select-Object -First 1
        if ($null -eq $firstLine) { return $Fallback }

        $value = ([string]$firstLine).Trim()
        if ([string]::IsNullOrWhiteSpace($value)) { return $Fallback }
        return $value
    }
    catch {
        return $Fallback
    }
}

function Get-ShortCommit {
    param([string]$Value)

    $trimmed = $Value.Trim()
    if ($trimmed.Length -le 12) { return $trimmed }
    return $trimmed.Substring(0, 12)
}

$gitCommit = Get-GitValue -Arguments @("rev-parse", "--short=12", "HEAD") -Fallback "unknown"
$gitBranch = Get-GitValue -Arguments @("branch", "--show-current") -Fallback "unknown"
$commit = if ([string]::IsNullOrWhiteSpace($SourceCommit)) { $gitCommit } else { Get-ShortCommit $SourceCommit }
$branch = if ([string]::IsNullOrWhiteSpace($SourceBranch)) { $gitBranch } else { $SourceBranch.Trim() }
$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$evidenceBase = if ([System.IO.Path]::IsPathRooted($EvidenceRoot)) {
    $EvidenceRoot
} else {
    Join-Path $repositoryRoot $EvidenceRoot
}
$evidenceDirectory = Join-Path $evidenceBase "$timestamp-$commit-$($Mode.ToLowerInvariant())"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

$commonArguments = @("--stacktrace", "--console=plain")
if ($NoDaemon) {
    $commonArguments += "--no-daemon"
}

$steps = [System.Collections.Generic.List[object]]::new()

function Add-Step {
    param(
        [string]$Name,
        [string[]]$Arguments
    )

    $steps.Add([pscustomobject]@{
        Name = $Name
        Arguments = $Arguments
    })
}

Add-Step -Name "gradle-version" -Arguments @("--version")
Add-Step -Name "build-logic-tests" -Arguments @("-p", "build-logic", ":convention:test")
Add-Step -Name "configuration-cache" -Arguments @("help", "--configuration-cache")
Add-Step -Name "configuration-cache-reuse" -Arguments @("help", "--configuration-cache")
Add-Step -Name "pure-kotlin-tests" -Arguments @(
    ":core:common:test",
    ":core:model:test",
    ":catalog:api:test",
    ":player:api:test",
    ":player:fake:test"
)
Add-Step -Name "android-unit-tests" -Arguments @(
    ":app:tv:testDebugUnitTest",
    ":core:credentials:testDebugUnitTest",
    ":core:database:testDebugUnitTest",
    ":core:designsystem:testDebugUnitTest",
    ":core:network:testDebugUnitTest",
    ":core:ui:testDebugUnitTest",
    ":player:media3:testDebugUnitTest",
    ":feature:home:testDebugUnitTest"
)
Add-Step -Name "credentials-instrumentation-compile" -Arguments @(
    ":core:credentials:assembleDebugAndroidTest"
)
Add-Step -Name "debug-apk" -Arguments @(
    ":app:tv:assembleDebug"
)

if ($Mode -in @("Full", "Device")) {
    Add-Step -Name "android-lint" -Arguments @(
        ":app:tv:lintDebug",
        ":core:credentials:lintDebug",
        ":core:database:lintDebug",
        ":core:designsystem:lintDebug",
        ":core:network:lintDebug",
        ":core:ui:lintDebug",
        ":player:media3:lintDebug",
        ":feature:home:lintDebug"
    )
    Add-Step -Name "release-assembly" -Arguments @(
        ":app:tv:assembleRelease"
    )
}

if ($Mode -eq "Device") {
    Add-Step -Name "credentials-device-tests" -Arguments @(
        ":core:credentials:connectedDebugAndroidTest"
    )
    Add-Step -Name "database-device-tests" -Arguments @(
        ":core:database:connectedDebugAndroidTest"
    )
    Add-Step -Name "app-device-tests" -Arguments @(
        ":app:tv:connectedDebugAndroidTest"
    )
}

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $branch
    commit = $commit
    mode = $Mode
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    steps = @()
}

$manifestPath = Join-Path $evidenceDirectory "manifest.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

try {
    foreach ($step in $steps) {
        $safeName = $step.Name -replace "[^a-zA-Z0-9._-]", "-"
        $logPath = Join-Path $evidenceDirectory "$safeName.log"
        $arguments = @($commonArguments + $step.Arguments)
        $startedAt = (Get-Date).ToUniversalTime()

        Write-Host "`n==> $($step.Name)"
        Write-Host "$gradleWrapper $($arguments -join ' ')"

        & $gradleWrapper @arguments 2>&1 | Tee-Object -FilePath $logPath
        $exitCode = $LASTEXITCODE
        $completedAt = (Get-Date).ToUniversalTime()

        $manifest.steps += [ordered]@{
            name = $step.Name
            arguments = $arguments
            startedAtUtc = $startedAt.ToString("o")
            completedAtUtc = $completedAt.ToString("o")
            durationSeconds = [Math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
            exitCode = $exitCode
            log = (Resolve-Path -Relative $logPath)
        }
        $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8

        if ($exitCode -ne 0) {
            throw "Verification step '$($step.Name)' failed with exit code $exitCode. See $logPath"
        }
    }

    $manifest.status = "passed"
}
catch {
    $manifest.status = "failed"
    $manifest.failure = $_.Exception.Message
    throw
}
finally {
    $manifest.completedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -Path $manifestPath -Encoding utf8
    Write-Host "`nEvidence: $evidenceDirectory"
}
