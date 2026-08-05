[CmdletBinding()]
param(
    [ValidateSet("Fast", "Full", "Device", "DeviceOnly")]
    [string]$Mode = "Fast",

    [ValidateSet("Product", "Database")]
    [string]$ConnectedSuite = "Product",

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

function Assert-AndroidTestCount {
    param(
        [string]$ModulePath,
        [string]$DisplayName
    )

    $resultsRoot = Join-Path $repositoryRoot (Join-Path $ModulePath "build\outputs\androidTest-results")
    if (-not (Test-Path $resultsRoot -PathType Container)) {
        throw "$DisplayName produced no Android test result directory: $resultsRoot"
    }

    $resultFiles = @(Get-ChildItem -Path $resultsRoot -Recurse -File -Filter "TEST-*.xml")
    if ($resultFiles.Count -eq 0) {
        throw "$DisplayName produced no TEST-*.xml results under $resultsRoot"
    }

    $testCount = 0
    $failureCount = 0
    $errorCount = 0
    $skippedCount = 0
    foreach ($resultFile in $resultFiles) {
        [xml]$document = Get-Content -Path $resultFile.FullName -Raw
        foreach ($suite in @($document.SelectNodes("//testsuite"))) {
            if ($null -ne $suite.Attributes["tests"]) {
                $testCount += [int]$suite.Attributes["tests"].Value
            }
            if ($null -ne $suite.Attributes["failures"]) {
                $failureCount += [int]$suite.Attributes["failures"].Value
            }
            if ($null -ne $suite.Attributes["errors"]) {
                $errorCount += [int]$suite.Attributes["errors"].Value
            }
            if ($null -ne $suite.Attributes["skipped"]) {
                $skippedCount += [int]$suite.Attributes["skipped"].Value
            }
        }
    }

    if ($testCount -lt 1) {
        throw "$DisplayName executed zero tests. A successful Gradle task without tests is not validation."
    }
    if (($failureCount + $errorCount) -gt 0) {
        throw "$DisplayName XML reports failures=$failureCount errors=$errorCount."
    }

    Write-Host "$DisplayName executed $testCount test(s); skipped=$skippedCount."
    return [ordered]@{
        module = $ModulePath
        displayName = $DisplayName
        tests = $testCount
        failures = $failureCount
        errors = $errorCount
        skipped = $skippedCount
        resultFiles = $resultFiles.Count
    }
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

if ($Mode -ne "DeviceOnly") {
    Add-Step -Name "gradle-version" -Arguments @("--version")
    Add-Step -Name "build-logic-tests" -Arguments @("-p", "build-logic", ":convention:test")
    Add-Step -Name "configuration-cache" -Arguments @("help", "--configuration-cache")
    Add-Step -Name "configuration-cache-reuse" -Arguments @("help", "--configuration-cache")
    Add-Step -Name "pure-kotlin-tests" -Arguments @(
        ":core:common:test",
        ":core:model:test",
        ":core:testing:test",
        ":catalog:api:test",
        ":catalog:ingest:test",
        ":player:api:test",
        ":player:fake:test"
    )
    Add-Step -Name "android-unit-tests" -Arguments @(
        ":app:tv:testDebugUnitTest",
        ":catalog:importer:testDebugUnitTest",
        ":catalog:onboarding:testDebugUnitTest",
        ":catalog:refresh:testDebugUnitTest",
        ":catalog:sync:testDebugUnitTest",
        ":core:credentials:testDebugUnitTest",
        ":core:database:testDebugUnitTest",
        ":core:designsystem:testDebugUnitTest",
        ":core:network:testDebugUnitTest",
        ":core:ui:testDebugUnitTest",
        ":feature:channels:testDebugUnitTest",
        ":feature:home:testDebugUnitTest",
        ":feature:search:testDebugUnitTest",
        ":feature:player:testDebugUnitTest",
        ":feature:sources:testDebugUnitTest",
        ":player:media3:testDebugUnitTest"
    )
    Add-Step -Name "android-instrumentation-compile" -Arguments @(
        ":catalog:importer:assembleDebugAndroidTest",
        ":catalog:refresh:assembleDebugAndroidTest",
        ":core:credentials:assembleDebugAndroidTest",
        ":core:database:assembleDebugAndroidTest",
        ":player:media3:assembleDebugAndroidTest",
        ":app:tv:assembleDebugAndroidTest"
    )
    Add-Step -Name "debug-apk" -Arguments @(
        ":app:tv:assembleDebug"
    )

    if ($Mode -in @("Full", "Device")) {
        Add-Step -Name "android-lint" -Arguments @(
            ":app:tv:lintDebug",
            ":catalog:importer:lintDebug",
            ":catalog:onboarding:lintDebug",
            ":catalog:refresh:lintDebug",
            ":catalog:sync:lintDebug",
            ":core:credentials:lintDebug",
            ":core:database:lintDebug",
            ":core:designsystem:lintDebug",
            ":core:network:lintDebug",
            ":core:ui:lintDebug",
            ":feature:channels:lintDebug",
            ":feature:home:lintDebug",
            ":feature:search:lintDebug",
            ":feature:player:lintDebug",
            ":feature:sources:lintDebug",
            ":player:media3:lintDebug"
        )
        Add-Step -Name "release-assembly" -Arguments @(
            ":app:tv:assembleRelease"
        )
    }
}

$connectedTestCatalog = @(
    [ordered]@{
        Suites = @("Product", "Database")
        StepName = "importer-epg-device-tests"
        GradleTask = ":catalog:importer:connectedDebugAndroidTest"
        ModulePath = "catalog\importer"
        DisplayName = "Importer EPG integration"
    },
    [ordered]@{
        Suites = @("Product")
        StepName = "remote-epg-device-tests"
        GradleTask = ":catalog:refresh:connectedDebugAndroidTest"
        ModulePath = "catalog\refresh"
        DisplayName = "Remote EPG integration"
    },
    [ordered]@{
        Suites = @("Product")
        StepName = "credentials-device-tests"
        GradleTask = ":core:credentials:connectedDebugAndroidTest"
        ModulePath = "core\credentials"
        DisplayName = "Credential instrumentation"
    },
    [ordered]@{
        Suites = @("Product", "Database")
        StepName = "database-device-tests"
        GradleTask = ":core:database:connectedDebugAndroidTest"
        ModulePath = "core\database"
        DisplayName = "Database instrumentation"
    },
    [ordered]@{
        Suites = @("Product")
        StepName = "media3-device-tests"
        GradleTask = ":player:media3:connectedDebugAndroidTest"
        ModulePath = "player\media3"
        DisplayName = "Media3 instrumentation"
    },
    [ordered]@{
        Suites = @("Product")
        StepName = "app-device-tests"
        GradleTask = ":app:tv:connectedDebugAndroidTest"
        ModulePath = "app\tv"
        DisplayName = "Application instrumentation"
    }
)
$deviceTestModules = @(
    $connectedTestCatalog | Where-Object { $_.Suites -contains $ConnectedSuite }
)
if ($deviceTestModules.Count -lt 1) {
    throw "Connected suite '$ConnectedSuite' selected no instrumentation modules."
}

if ($Mode -in @("Device", "DeviceOnly")) {
    foreach ($module in $deviceTestModules) {
        $staleResults = Join-Path $repositoryRoot (Join-Path $module.ModulePath "build\outputs\androidTest-results")
        Remove-Item -Path $staleResults -Recurse -Force -ErrorAction SilentlyContinue
    }

    foreach ($module in $deviceTestModules) {
        Add-Step -Name $module.StepName -Arguments @($module.GradleTask)
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    repository = "MuxTV/Muxtv"
    branch = $branch
    commit = $commit
    mode = $Mode
    connectedSuite = $ConnectedSuite
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    completedAtUtc = $null
    status = "running"
    steps = @()
    instrumentationTests = @()
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

    $roomSchemaDirectory = Join-Path $repositoryRoot `
        "core\database\schemas\app.muxtv.database.MuxTvDatabase"
    if (Test-Path $roomSchemaDirectory -PathType Container) {
        $latestRoomSchema = @(
            Get-ChildItem -Path $roomSchemaDirectory -File -Filter "*.json" |
                Where-Object { $_.BaseName -match '^\d+$' } |
                Sort-Object { [int]$_.BaseName }
        ) | Select-Object -Last 1
        if ($null -ne $latestRoomSchema) {
            Copy-Item `
                -Path $latestRoomSchema.FullName `
                -Destination (Join-Path $evidenceDirectory "room-schema-$($latestRoomSchema.BaseName).json") `
                -Force
        }
    }

    if ($Mode -in @("Device", "DeviceOnly")) {
        foreach ($module in $deviceTestModules) {
            $manifest.instrumentationTests += Assert-AndroidTestCount `
                -ModulePath $module.ModulePath `
                -DisplayName $module.DisplayName
        }
        $countPath = Join-Path $evidenceDirectory "instrumentation-test-counts.json"
        $manifest.instrumentationTests | ConvertTo-Json -Depth 5 | Set-Content -Path $countPath -Encoding utf8
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
